package com.playfieldportal.feature.backup.restore

import com.playfieldportal.core.archive.ZipLimits
import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.IntentType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The restore path is the only place where one user action — opening a file — reaches both
 * arbitrary writes under `filesDir` and an attacker-chosen `ComponentName` that later receives a
 * URI grant. Before this seam existed, `BackupManagerTest` had nine tests and none of them covered
 * slip, bombs, or non-root paths.
 */
class RestoreArchiveTest {

    @get:Rule val temp = TemporaryFolder()

    private val roots = listOf("artwork", "wallpaper", "emulator_profiles")
    private val json = Json { ignoreUnknownKeys = true }

    private fun backup(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun read(
        bytes: ByteArray,
        limits: ZipLimits = ZipLimits(),
    ): RestoredBundle = RestoreArchive.read(
        source = ByteArrayInputStream(bytes),
        staging = temp.newFolder(),
        bundledRoots = roots,
        limits = limits,
    )

    private fun text(s: String) = s.toByteArray()

    // ── JSON entries ──────────────────────────────────────────────────────────

    @Test
    fun `json entries outside the files prefix are returned as text`() {
        val bundle = read(backup("manifest.json" to text("""{"formatVersion":1}""")))

        assertEquals("""{"formatVersion":1}""", bundle.jsonEntries["manifest.json"])
    }

    // ── Root confinement — the gap this seam closes ───────────────────────────

    @Test
    fun `a staged file under a bundled root is kept`() {
        val bundle = read(backup("files/artwork/cover.png" to text("png")))

        val filesDir = temp.newFolder("files")
        bundle.commitFiles(filesDir)

        assertEquals("png", File(filesDir, "artwork/cover.png").readText())
    }

    @Test
    fun `a staged file outside every bundled root is refused, not written`() {
        // The live DataStore lives here. Overwriting it turns the app into a crash loop, and it
        // is inside filesDir, so a staging-only confinement check let it through.
        val bundle = read(
            backup(
                "files/datastore/pfp.preferences_pb" to text("corrupt"),
                "files/artwork/cover.png" to text("png"),
            ),
        )

        val filesDir = temp.newFolder("files")
        bundle.commitFiles(filesDir)

        assertFalse(File(filesDir, "datastore/pfp.preferences_pb").exists())
        assertTrue(File(filesDir, "artwork/cover.png").exists())
        assertTrue(bundle.refusals.any { it.contains("datastore") })
    }

    @Test
    fun `a parent-traversal entry is refused`() {
        val bundle = read(backup("files/../../escaped.txt" to text("nope")))

        val filesDir = temp.newFolder("files")
        bundle.commitFiles(filesDir)

        assertFalse(File(temp.root, "escaped.txt").exists())
        assertTrue(bundle.refusals.isNotEmpty())
    }

    @Test
    fun `an absolute-path entry is refused`() {
        val bundle = read(backup("files//etc/passwd" to text("nope")))

        assertTrue(bundle.refusals.isNotEmpty())
        assertTrue(bundle.stagedCount == 0)
    }

    @Test
    fun `a root-name prefix is not a root`() {
        val bundle = read(backup("files/artwork_evil/x.png" to text("nope")))

        assertEquals(0, bundle.stagedCount)
        assertTrue(bundle.refusals.isNotEmpty())
    }

    // ── Bombs ─────────────────────────────────────────────────────────────────

    @Test(expected = com.playfieldportal.core.archive.ZipLimitExceededException::class)
    fun `a compression bomb in a json entry is refused instead of inflated`() {
        // 8 MB of one repeated byte deflates to a few KB. readBackup used to call an uncapped
        // zip.readBytes() here and hold the result for the whole restore.
        read(backup("games.json" to ByteArray(8 * 1024 * 1024)), ZipLimits(maxEntryBytes = 64 * 1024))
    }

    @Test(expected = com.playfieldportal.core.archive.ZipLimitExceededException::class)
    fun `an entry-count bomb is refused`() {
        val many = Array(40) { "e$it.json" to text("{}") }
        read(backup(*many), ZipLimits(maxEntries = 10))
    }

    // ── Emulator profile admission ────────────────────────────────────────────

    @Test
    fun `a restored profile carrying a custom command is stripped from the bundle`() {
        val good = EmulatorProfile(
            id = "good", name = "Good", packageName = "org.example.emu",
            activityClass = "org.example.emu.Main", intentType = IntentType.COMPONENT,
            supportedPlatformIds = listOf("psx"), isCustom = true,
        )
        val hostile = good.copy(
            id = "hostile", name = "Hostile",
            intentType = IntentType.CUSTOM_COMMAND, customCommand = "su -c whatever",
        )
        val payload = json.encodeToString(
            ListSerializer(EmulatorProfile.serializer()), listOf(good, hostile),
        )

        val bundle = read(backup("files/emulator_profiles/custom_profiles.json" to text(payload)))
        val filesDir = temp.newFolder("files")
        bundle.commitFiles(filesDir)

        val written = json.decodeFromString(
            ListSerializer(EmulatorProfile.serializer()),
            File(filesDir, "emulator_profiles/custom_profiles.json").readText(),
        )
        assertEquals(listOf("good"), written.map { it.id })
        assertTrue(bundle.refusals.any { it.contains("Hostile") })
    }

    @Test
    fun `an unparseable profiles file is dropped rather than committed verbatim`() {
        val bundle = read(backup("files/emulator_profiles/custom_profiles.json" to text("not json at all")))
        val filesDir = temp.newFolder("files")
        bundle.commitFiles(filesDir)

        assertFalse(File(filesDir, "emulator_profiles/custom_profiles.json").exists())
        assertTrue(bundle.refusals.isNotEmpty())
    }

    // ── Housekeeping ──────────────────────────────────────────────────────────

    @Test
    fun `commit clears the managed roots so a restore replaces rather than merges`() {
        val filesDir = temp.newFolder("files")
        File(filesDir, "artwork").mkdirs()
        File(filesDir, "artwork/stale.png").writeText("old")

        val bundle = read(backup("files/artwork/fresh.png" to text("new")))
        bundle.commitFiles(filesDir)

        assertFalse(File(filesDir, "artwork/stale.png").exists())
        assertTrue(File(filesDir, "artwork/fresh.png").exists())
    }

    @Test
    fun `a backup with no bundled files leaves the live roots untouched`() {
        val filesDir = temp.newFolder("files")
        File(filesDir, "artwork").mkdirs()
        File(filesDir, "artwork/keep.png").writeText("keep")

        val bundle = read(backup("manifest.json" to text("{}")))
        bundle.commitFiles(filesDir)

        assertTrue(File(filesDir, "artwork/keep.png").exists())
    }

    @Test
    fun `discard removes the staging directory`() {
        val bundle = read(backup("files/artwork/a.png" to text("x")))

        bundle.discard()

        assertFalse(bundle.stagingDir.exists())
    }
}
