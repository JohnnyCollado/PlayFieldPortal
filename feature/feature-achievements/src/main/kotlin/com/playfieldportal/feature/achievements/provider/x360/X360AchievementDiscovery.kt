package com.playfieldportal.feature.achievements.provider.x360

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.playfieldportal.core.data.repository.Xbox360DataLibrary
import com.playfieldportal.core.data.saf.SafChild
import com.playfieldportal.core.data.saf.querySafChildren
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads Xbox 360 achievements from the emulators' granted data folders ([Xbox360DataLibrary]).
 *
 * Both emulators are Xenia ports, so a profile lives at `content/<XUID>/FFFE07D1/00010000/<XUID>/`
 * and holds the dashboard `FFFE07D1.gpd` plus one `<TitleID>.gpd` per game played (see [XdbfGpd]).
 * The user may grant the emulator's root, `content/`, or anything in between, and X360 Mobile
 * exposes its data through its own documents provider, so profiles are found by a short, pruned
 * walk for any folder holding `FFFE07D1.gpd` rather than by a fixed path.
 *
 * Earned-only images are extracted to app storage so coin icons survive the cache being cleared.
 * Read-only and grant-scoped: nothing is ever written into an emulator's folder.
 */
@Singleton
class X360AchievementDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataLibrary: Xbox360DataLibrary,
) {
    data class Achievement(
        val id: Int,
        val title: String,
        val unlockedDescription: String,
        val lockedDescription: String,
        val gamerscore: Int,
        val secret: Boolean,
        val unlocked: Boolean,
        val unlockedAtEpochMillis: Long?,
        val iconUri: String?,
    )

    /** A game some linked profile has played. */
    data class PlayedTitle(val titleId: String, val name: String)

    sealed interface Load {
        data class Found(val titleId: String, val titleName: String?, val achievements: List<Achievement>) : Load
        /** Neither emulator's data folder is set. */
        data object NotConfigured : Load
        /** The folders are set but hold no Xenia profile. */
        data object NoProfile : Load
        /** Profiles exist, but none has played this title yet (Xenia writes its GPD on first boot). */
        data object NotPlayed : Load
    }

    // A profile folder: its tree and the GPD files directly inside it.
    private class Profile(val tree: Uri, val gpds: List<SafChild>)

    // Walking a folder over SAF is the slow part; one sync asks for every linked title in a burst.
    private var cachedProfiles: Pair<List<String>, List<Profile>>? = null
    private var cachedAt = 0L

    /** Every linked title's merged achievements, or why there are none. */
    suspend fun load(titleId: String): Load = withContext(Dispatchers.IO) {
        val grants = dataLibrary.grantedTreeUris()
        if (grants.isEmpty()) return@withContext Load.NotConfigured
        val profiles = profiles(grants)
        if (profiles.isEmpty()) return@withContext Load.NoProfile
        val wanted = "${titleId.uppercase()}.gpd"
        val gpds = profiles.flatMap { p -> p.gpds.filter { it.name.equals(wanted, ignoreCase = true) } }
            .mapNotNull { readBytes(it.uri)?.let(XdbfGpd::parse) }
        if (gpds.isEmpty()) return@withContext Load.NotPlayed
        val merged = X360Achievements.merge(gpds)
        Load.Found(
            titleId = titleId.uppercase(),
            titleName = gpds.firstNotNullOfOrNull { it.titleName },
            achievements = merged.map { m ->
                val a = m.achievement
                Achievement(
                    id = a.id,
                    title = a.title,
                    unlockedDescription = a.unlockedDescription,
                    lockedDescription = a.lockedDescription,
                    gamerscore = a.gamerscore,
                    secret = a.secret,
                    unlocked = a.unlocked,
                    unlockedAtEpochMillis = a.unlockedAtEpochMillis,
                    iconUri = m.icon?.let { iconFile(titleId, a.imageId, it) },
                )
            },
        )
    }

    /** Every title any linked profile has played, by its dashboard name. */
    suspend fun playedTitles(): List<PlayedTitle> = withContext(Dispatchers.IO) {
        val grants = dataLibrary.grantedTreeUris()
        if (grants.isEmpty()) return@withContext emptyList()
        val byId = linkedMapOf<String, String>()
        for (profile in profiles(grants)) {
            profile.gpds.firstOrNull { it.name.equals(DASHBOARD_GPD, ignoreCase = true) }
                ?.let { readBytes(it.uri) }?.let(XdbfGpd::parse)
                ?.titles?.forEach { byId.putIfAbsent(it.titleId, it.name) }
            // A title GPD the dashboard doesn't list still names itself.
            profile.gpds.filter { TITLE_GPD.matches(it.name) && it.name.substringBefore('.').uppercase() !in byId }
                .forEach { child ->
                    val name = readBytes(child.uri)?.let(XdbfGpd::parse)?.titleName ?: return@forEach
                    byId.putIfAbsent(child.name.substringBefore('.').uppercase(), name)
                }
        }
        byId.map { (id, name) -> PlayedTitle(id, name) }
    }

    private fun profiles(grants: List<String>): List<Profile> {
        val now = System.currentTimeMillis()
        cachedProfiles?.let { (keys, list) -> if (keys == grants && now - cachedAt < CACHE_MS) return list }
        val found = grants.flatMap { grant ->
            val tree = runCatching { Uri.parse(grant) }.getOrNull() ?: return@flatMap emptyList()
            val root = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
                ?: return@flatMap emptyList()
            runCatching { findProfiles(tree, root) }
                .onFailure { Timber.w(it, "Xbox 360 profile walk failed for %s", grant) }
                .getOrDefault(emptyList())
        }
        cachedProfiles = grants to found
        cachedAt = now
        return found
    }

    // Breadth-first, pruned: Xenia's content folder also holds every save, DLC and title update
    // under 8-hex-digit title folders, and none of those can contain a profile.
    private fun findProfiles(tree: Uri, rootDocId: String): List<Profile> {
        val profiles = mutableListOf<Profile>()
        var level = listOf(rootDocId)
        repeat(MAX_DEPTH) {
            val next = mutableListOf<String>()
            for (docId in level) {
                val children = context.contentResolver.querySafChildren(tree, docId)
                if (children.any { !it.isDirectory && it.name.equals(DASHBOARD_GPD, ignoreCase = true) }) {
                    profiles += Profile(tree, children.filter { !it.isDirectory && it.name.endsWith(".gpd", ignoreCase = true) })
                    continue
                }
                children.filter { it.isDirectory && worthEntering(it.name) }.mapTo(next) { it.documentId }
            }
            if (next.isEmpty()) return profiles
            level = next.take(MAX_DIRS_PER_LEVEL)
        }
        return profiles
    }

    private fun worthEntering(name: String): Boolean {
        if (name.startsWith(".") || name.lowercase() in SKIPPED_DIRS) return false
        if (name == MACHINE_XUID) return false   // shared DLC/updates, never a profile
        // Of the 8-hex-digit folders only the profile path's own two are entered:
        // <XUID>/FFFE07D1/00010000/<XUID>.
        if (TITLE_DIR.matches(name)) return name.uppercase() in PROFILE_PATH_DIRS
        return true
    }

    private fun iconFile(titleId: String, imageId: Int, png: ByteArray): String? = runCatching {
        val dir = File(context.filesDir, "x360_achievements/${titleId.uppercase()}").apply { mkdirs() }
        val file = File(dir, "$imageId.png")
        if (!file.exists() || file.length() != png.size.toLong()) file.writeBytes(png)
        Uri.fromFile(file).toString()
    }.onFailure { Timber.w(it, "Couldn't cache Xbox 360 achievement icon") }.getOrNull()

    private fun readBytes(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var remaining = MAX_GPD_BYTES
            while (remaining > 0) {
                val read = input.read(buf, 0, minOf(buf.size, remaining))
                if (read == -1) break
                out.write(buf, 0, read)
                remaining -= read
            }
            out.toByteArray()
        }
    }.getOrNull()

    private companion object {
        const val DASHBOARD_GPD = "FFFE07D1.gpd"
        val PROFILE_PATH_DIRS = setOf("FFFE07D1", "00010000")
        const val MACHINE_XUID = "0000000000000000"
        // A profile sits 7 levels under Android/data/xendroid.compose; a grant one level higher still fits.
        const val MAX_DEPTH = 10
        const val MAX_DIRS_PER_LEVEL = 200
        const val MAX_GPD_BYTES = 4 * 1024 * 1024
        const val CACHE_MS = 60_000L
        val TITLE_DIR = Regex("[0-9A-Fa-f]{8}")
        val TITLE_GPD = Regex("[0-9A-Fa-f]{8}\\.gpd", RegexOption.IGNORE_CASE)
        // Emulator folders that never hold profiles and can be large.
        val SKIPPED_DIRS = setOf("cache", "cache0", "cache1", "scratch", "patches", "game-patches", "shaders", "logs", "driver", "drivers")
    }
}
