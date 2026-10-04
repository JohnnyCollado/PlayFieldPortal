package com.playfieldportal.feature.launcher

import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameContentType
import com.playfieldportal.core.domain.repository.GameRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Console emulators whose home-screen shortcuts belong on their console's platform Memory Card
 * rather than in a custom card named after the app.
 */
object ConsoleShortcutHosts {

    private class Host(val platformId: String, val idIsGamePath: Boolean)

    private val hosts = mapOf(
        // X360 Mobile: shortcut ids are opaque hashes (x360-game-<md5>), so the label is the handle.
        "emu.x360mobile.com" to Host("x360", idIsGamePath = false),
        // XenDroid: the shortcut id is the game's absolute path.
        "xendroid.compose" to Host("x360", idIsGamePath = true),
        "xendroid.compose.debug" to Host("x360", idIsGamePath = true),
    )

    /** The platform [hostPackage]'s shortcuts belong to, or null when it isn't a console host. */
    fun platformFor(hostPackage: String?): String? = hosts[hostPackage]?.platformId

    internal fun idIsGamePath(hostPackage: String): Boolean = hosts[hostPackage]?.idIsGamePath == true
}

/** Outcome of one console shortcut import: the game it landed on, and whether that was an existing one. */
data class ConsoleShortcutImportResult(val gameId: Long, val linked: Boolean)

/**
 * Files a pinned shortcut from a console emulator ([ConsoleShortcutHosts]) on that console's
 * platform Memory Card. A shortcut for a game the library already has (same file for XenDroid,
 * same name for X360 Mobile) links to it — the game is set to launch with the emulator that
 * pinned it — instead of adding a second entry. Anything else becomes a new game on the card,
 * launched through the shortcut itself; the card is created if the library has none yet.
 */
@Singleton
class ConsoleShortcutImporter @Inject constructor(
    private val gameRepository: GameRepository,
    private val memoryCards: MemoryCardRepository,
) {
    fun isConsoleHost(hostPackage: String?): Boolean = ConsoleShortcutHosts.platformFor(hostPackage) != null

    suspend fun importPinnedShortcut(hostPackage: String, shortcutId: String, label: String): ConsoleShortcutImportResult {
        val platformId = ConsoleShortcutHosts.platformFor(hostPackage)
            ?: error("$hostPackage is not a console shortcut host")

        // Re-pinning the same shortcut changes nothing.
        gameRepository.getLauncherShortcut(hostPackage, shortcutId)?.let {
            return ConsoleShortcutImportResult(it.id, linked = false)
        }

        val games = gameRepository.getByPlatform(platformId)
        val match = games.firstOrNull { ConsoleShortcutHosts.idIsGamePath(hostPackage) && it.romPath == shortcutId }
            ?: normalizeTitle(label).takeIf { it.isNotEmpty() }?.let { key ->
                games.firstOrNull { g -> g.shortcutId == null && (normalizeTitle(g.displayTitle) == key || normalizeTitle(g.title) == key) }
            }
        if (match != null) {
            gameRepository.setPreferredEmulator(match.id, hostPackage)
            Timber.i("Console shortcut \"$label\" from $hostPackage linked to \"${match.displayTitle}\"")
            return ConsoleShortcutImportResult(match.id, linked = true)
        }

        ensureCard(platformId)
        val gameId = gameRepository.upsert(
            Game(
                title         = label,
                platformId    = platformId,
                packageName   = hostPackage,
                shortcutId    = shortcutId,
                isManualEntry = true,
                contentType   = GameContentType.GAME,
            ),
        )
        runCatching { memoryCards.recountGames(platformId) }
        Timber.i("Console shortcut \"$label\" from $hostPackage added to $platformId")
        return ConsoleShortcutImportResult(gameId, linked = false)
    }

    private suspend fun ensureCard(platformId: String) {
        val platform = memoryCards.unconfiguredPlatforms().firstOrNull { it.id == platformId } ?: return
        memoryCards.addCard(
            platformId = platformId,
            displayName = "${platform.name} Memory Card",
            romDirectory = null,
            emulatorId = null,
        )
    }

    private companion object {
        // Region/language tags never block a match: "Dead or Alive 4 (Asia) (En,Ja)" = "DEAD OR ALIVE 4".
        val TAGS = Regex("""\([^)]*\)|\[[^]]*]""")

        fun normalizeTitle(title: String): String =
            title.replace(TAGS, " ").lowercase().filter { it.isLetterOrDigit() }
    }
}
