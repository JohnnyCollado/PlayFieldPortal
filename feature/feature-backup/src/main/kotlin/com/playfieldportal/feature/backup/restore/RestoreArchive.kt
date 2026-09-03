package com.playfieldportal.feature.backup.restore

import com.playfieldportal.core.archive.BoundedZipReader
import com.playfieldportal.core.archive.SafeArchivePath
import com.playfieldportal.core.archive.ZipLimits
import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.EmulatorProfileAdmission
import com.playfieldportal.feature.backup.BACKUP_FILES_PREFIX
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.InputStream

/**
 * The trust seam for `.pfpbackup` restore.
 *
 * A backup file is untrusted input that reaches further than anything else the app opens: it
 * writes into `filesDir` and it supplies emulator profiles, which choose the `ComponentName` an
 * intent is aimed at and whose package later receives `grantUriPermission(...)` for a ROM.
 *
 * The confinement policy used to exist as a comment and a delete loop rather than as an enforced
 * interface: `BUNDLED_FILE_ROOTS` was used only to clear directories, and the copy that followed
 * resolved `File(filesDir, rel)` with `rel` never checked against those roots. Anything under
 * `filesDir` was therefore writable — including the DataStore preference file, which corrupts the
 * live preference store into a crash loop.
 *
 * Here the invariants live in one module and callers cannot opt out of them: an entry that is not
 * under a declared root never reaches staging, so [RestoredBundle.commitFiles] has nothing unsafe
 * left to copy. Bounds come from [BoundedZipReader], so this reader is capped the same way the
 * theme readers are.
 */
object RestoreArchive {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The one profile file a backup may carry, relative to `filesDir`. */
    private const val PROFILES_PATH = "emulator_profiles/custom_profiles.json"

    /**
     * Reads and validates [source]. Bundled files land in [staging]; JSON tables come back in
     * memory. Throws [com.playfieldportal.core.archive.ZipLimitExceededException] if the archive
     * exceeds [limits] — a bomb is refused rather than partially applied.
     *
     * @param bundledRoots the `filesDir` sub-trees a backup is allowed to own.
     */
    fun read(
        source: InputStream,
        staging: File,
        bundledRoots: List<String>,
        limits: ZipLimits = ZipLimits(),
        selfPackage: String? = null,
    ): RestoredBundle {
        staging.deleteRecursively()
        staging.mkdirs()

        val jsonEntries = mutableMapOf<String, String>()
        val refusals = mutableListOf<String>()
        var staged = 0

        BoundedZipReader.read(source, limits) { entry ->
            if (entry.isDirectory) return@read

            if (!entry.name.startsWith(BACKUP_FILES_PREFIX)) {
                jsonEntries[entry.name] = entry.readBytes().toString(Charsets.UTF_8)
                return@read
            }

            val relative = entry.name.removePrefix(BACKUP_FILES_PREFIX)
            // One call answers both "does it escape?" and "is it under a root we own?". Returning
            // a File? rather than a boolean is what stops the check being made and then ignored,
            // which is precisely what happened before.
            val dest = SafeArchivePath.resolveWithinRoots(staging, relative, bundledRoots)
            if (dest == null) {
                refusals += "Refused '$relative': not inside a restorable folder"
                Timber.w("Restore refused out-of-root entry: %s", relative)
                return@read
            }
            dest.parentFile?.mkdirs()
            dest.outputStream().use { out -> entry.copyTo(out) }
            staged++
        }

        // Profiles are validated before anything is committed, so an inadmissible one never
        // reaches disk — not even briefly.
        staged -= sanitizeProfiles(staging, refusals, selfPackage)

        return RestoredBundle(jsonEntries, staging, bundledRoots, refusals, staged)
    }

    /**
     * Filters the staged profile file through [EmulatorProfileAdmission], rewriting it in place.
     * Returns how many staged files were removed entirely (0 or 1).
     */
    private fun sanitizeProfiles(
        staging: File,
        refusals: MutableList<String>,
        selfPackage: String?,
    ): Int {
        val file = File(staging, PROFILES_PATH)
        if (!file.exists()) return 0

        val parsed = runCatching {
            json.decodeFromString(ListSerializer(EmulatorProfile.serializer()), file.readText())
        }.getOrElse { e ->
            // An unreadable profile file is dropped rather than committed verbatim: the repository
            // that reads it later would swallow the parse failure and the user would see profiles
            // silently missing with no explanation of why.
            refusals += "Refused emulator profiles: file could not be parsed"
            Timber.w(e, "Restore refused unparseable emulator profiles")
            file.delete()
            return 1
        }

        val result = EmulatorProfileAdmission.admit(parsed, selfPackage)
        result.refused.forEach {
            refusals += "Refused emulator profile '${it.name}': ${it.reason}"
            Timber.w("Restore refused emulator profile %s: %s", it.id, it.reason)
        }
        if (result.refused.isEmpty()) return 0

        if (result.admitted.isEmpty()) {
            file.delete()
            return 1
        }
        file.writeText(
            json.encodeToString(ListSerializer(EmulatorProfile.serializer()), result.admitted),
        )
        return 0
    }
}

/**
 * A backup that has already been checked. Every value here is admissible by construction, so a
 * caller cannot reintroduce the class of bug this seam exists to prevent.
 */
class RestoredBundle internal constructor(
    /** Non-file entries, keyed by archive entry name. */
    val jsonEntries: Map<String, String>,
    /** Where validated files are staged until [commitFiles]. */
    val stagingDir: File,
    private val bundledRoots: List<String>,
    /** Human-readable notes about what was turned away, for the restore summary. */
    val refusals: List<String>,
    /** How many files survived validation. */
    val stagedCount: Int,
) {

    /**
     * Moves the staged files into [filesDir], replacing the managed roots wholesale.
     *
     * The roots are cleared first so a restore is a replace rather than a merge — but only when
     * the backup actually carried files, so restoring a settings-only backup does not wipe the
     * user's artwork.
     */
    fun commitFiles(filesDir: File) {
        val staged = stagingDir.takeIf { it.exists() }
            ?.walkTopDown()?.filter { it.isFile }?.toList().orEmpty()
        if (staged.isEmpty()) {
            discard()
            return
        }
        bundledRoots.forEach { root -> File(filesDir, root).deleteRecursively() }
        staged.forEach { src ->
            val relative = src.relativeTo(stagingDir).invariantSeparatorsPath
            // Belt and braces: staging already contains only root members, so this cannot fail —
            // but the copy is the dangerous step, and it now refuses rather than trusting its input.
            val dest = com.playfieldportal.core.archive.SafeArchivePath
                .resolveWithinRoots(filesDir, relative, bundledRoots)
            if (dest == null) {
                Timber.e("Staged file escaped confinement at commit time: %s", relative)
                return@forEach
            }
            dest.parentFile?.mkdirs()
            src.copyTo(dest, overwrite = true)
        }
        discard()
    }

    /** Deletes the staging directory without committing anything. */
    fun discard() {
        stagingDir.deleteRecursively()
    }
}
