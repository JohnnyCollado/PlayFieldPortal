package com.playfieldportal.feature.settings.pc

import com.playfieldportal.core.data.database.entity.ArtworkRecordEntity
import com.playfieldportal.core.data.model.StorefrontIdentity
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameContentType
import com.playfieldportal.feature.launcher.PcLauncherCatalog

private const val WINDOWS_PLATFORM_ID = "windows"

/** The keys a Windows game is recognised by, shared by Export Manual Games and the import. */
internal object WindowsGameKeys {

    private const val GAMEHUB_FAMILY = "gamehub-family"

    /**
     * The game's stored (store, id) pair, or the one its launch intent names. Add by ID records no
     * storefront columns, so a GameNative game added by id is only recognisable through its intent.
     */
    fun storefrontPairOf(game: Game): Pair<String, String>? {
        val store = game.storefront
        val id = game.storefrontGameId
        if (store != null && id != null) return store to id
        return StorefrontIdentity.fromLaunchIntentUri(game.launchIntentUri)
    }

    /** The export entry's pair, read the same way as [storefrontPairOf] for a game. */
    fun storefrontPairOf(export: PcGameExport): Pair<String, String>? {
        val store = StorefrontIdentity.normalizeStore(export.storefront)
        val id = export.storefrontGameId
        if (store != null && id != null) return store to id
        return StorefrontIdentity.fromLaunchIntentUri(export.launchIntentUri)
    }

    /**
     * The launcher a package stands for. The GameHub family ships under several package names
     * (`gamehub.lite`, `banner.hub`, `com.xiaoji.egggame`, …) and a user can switch between them, so
     * every one of them is the same launcher here.
     */
    fun launcherKey(packageName: String?): String? {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (PcLauncherCatalog.isGameHubFamilyPackage(pkg)) GAMEHUB_FAMILY else pkg
    }

    /** Letters and digits only, lowercased — the Windows-card dedupe rule (`PcGameScanner`, `PcShortcutImporter`). */
    fun normalizeTitle(title: String): String = title.lowercase().filter { it.isLetterOrDigit() }
}

/** What the Android side found out about a `.pfpgame` entry's launch intent, before the planner decides. */
data class LaunchCheck(
    /** The entry's launcher is installed and is a PC launcher PFP trusts. */
    val launcherVerified: Boolean,
    /** The package the intent actually targets (its component's, else its package), or null if it did not parse. */
    val intentPackage: String?,
    /** The intent after `ShortcutIntentSanitizer`, as a URI, or null when it could not be made safe. */
    val sanitizedIntentUri: String?,
)

enum class PcGameImportSkip {
    /** The entry's launcher is not installed, or is not a PC launcher PFP trusts. */
    LAUNCHER_UNAVAILABLE,

    /** The launch intent targets another app, did not parse, or could not be made safe. */
    UNTRUSTED_INTENT,

    /** More than one library game fits the entry by title, so picking one would be a guess. */
    AMBIGUOUS,

    /** A pin entry for a pin that is not in the library; pins are never created from a file. */
    PIN_NOT_IN_LIBRARY,
}

sealed interface PcGameImportDecision {
    /** No game matched: create [game]. */
    data class Create(val game: Game) : PcGameImportDecision

    /** [game] is the matched game, filled from the entry; [changed] says whether anything was filled. */
    data class Fill(val game: Game, val changed: Boolean) : PcGameImportDecision

    data class Skip(val reason: PcGameImportSkip) : PcGameImportDecision
}

/**
 * Decides what one `.pfpgame` entry does to the library (C18 task X.4). Pure: the Android checks
 * arrive as a [LaunchCheck], and the scan applies the decision.
 */
object PcGameImportPlanner {

    /**
     * @param launch the launch-intent check; null for a pin entry, which carries no intent.
     * @param windowsGames the library's Windows games as they are now, including any the same scan
     *   has already created, so an entry never duplicates them.
     */
    fun plan(export: PcGameExport, launch: LaunchCheck?, windowsGames: List<Game>): PcGameImportDecision {
        val games = windowsGames.filter { it.platformId == WINDOWS_PLATFORM_ID }

        if (export.isPin) {
            val match = games.firstOrNull { it.packageName == export.launcherPackage && it.shortcutId == export.shortcutId }
                ?: when (val byTitle = byLauncherAndTitle(export, games)) {
                    is TitleMatch.Unique -> byTitle.game
                    TitleMatch.Ambiguous -> return PcGameImportDecision.Skip(PcGameImportSkip.AMBIGUOUS)
                    TitleMatch.None -> return PcGameImportDecision.Skip(PcGameImportSkip.PIN_NOT_IN_LIBRARY)
                }
            return fill(match, export)
        }

        if (launch == null || !launch.launcherVerified) return PcGameImportDecision.Skip(PcGameImportSkip.LAUNCHER_UNAVAILABLE)
        val sanitized = launch.sanitizedIntentUri
        if (sanitized == null || launch.intentPackage != export.launcherPackage) {
            return PcGameImportDecision.Skip(PcGameImportSkip.UNTRUSTED_INTENT)
        }

        // 1. The same launch intent, as written in the file or as sanitized now.
        val intentUris = setOfNotNull(export.launchIntentUri, sanitized)
        games.firstOrNull { it.launchIntentUri in intentUris }?.let { return fill(it, export) }

        // 2. The same storefront pair. A Steam 620 is never a GOG 620.
        WindowsGameKeys.storefrontPairOf(export)?.let { pair ->
            games.firstOrNull { WindowsGameKeys.storefrontPairOf(it) == pair }?.let { return fill(it, export) }
        }

        // 3. The same launcher and title, when exactly one game fits.
        return when (val byTitle = byLauncherAndTitle(export, games)) {
            is TitleMatch.Unique -> fill(byTitle.game, export)
            TitleMatch.Ambiguous -> PcGameImportDecision.Skip(PcGameImportSkip.AMBIGUOUS)
            TitleMatch.None -> PcGameImportDecision.Create(create(export, sanitized))
        }
    }

    private sealed interface TitleMatch {
        data class Unique(val game: Game) : TitleMatch
        data object Ambiguous : TitleMatch
        data object None : TitleMatch
    }

    private fun byLauncherAndTitle(export: PcGameExport, games: List<Game>): TitleMatch {
        val launcher = WindowsGameKeys.launcherKey(export.launcherPackage)
        val wanted = titleKeys(export.title, export.scrapedTitle, export.userTitleOverride)
        if (launcher == null || wanted.isEmpty()) return TitleMatch.None
        val fits = games.filter { game ->
            WindowsGameKeys.launcherKey(game.packageName) == launcher &&
                titleKeys(game.displayTitle, game.title, game.scrapedTitle).any { it in wanted }
        }
        return when (fits.size) {
            0 -> TitleMatch.None
            1 -> TitleMatch.Unique(fits.single())
            else -> TitleMatch.Ambiguous
        }
    }

    private fun titleKeys(vararg titles: String?): Set<String> =
        titles.mapNotNull { it?.let(WindowsGameKeys::normalizeTitle)?.takeIf(String::isNotEmpty) }.toSet()

    /** Fill-only: every value the game already has stays; only a missing one takes the entry's. */
    private fun fill(game: Game, export: PcGameExport): PcGameImportDecision.Fill {
        val takeStorefront = game.storefront == null && game.storefrontGameId == null &&
            export.storefront != null && export.storefrontGameId != null
        val filled = game.copy(
            scrapedTitle = game.scrapedTitle ?: export.scrapedTitle,
            userTitleOverride = game.userTitleOverride ?: export.userTitleOverride,
            storefront = if (takeStorefront) export.storefront else game.storefront,
            storefrontGameId = if (takeStorefront) export.storefrontGameId else game.storefrontGameId,
            ssId = game.ssId ?: export.ssId,
            tgdbId = game.tgdbId ?: export.tgdbId,
            igdbId = game.igdbId ?: export.igdbId,
            steamGridDbId = game.steamGridDbId ?: export.steamGridDbId,
        )
        return PcGameImportDecision.Fill(filled, changed = filled != game)
    }

    private fun create(export: PcGameExport, sanitizedIntentUri: String): Game {
        val hasStorefront = export.storefront != null && export.storefrontGameId != null
        return Game(
            title = export.title,
            platformId = WINDOWS_PLATFORM_ID,
            packageName = export.launcherPackage,
            isManualEntry = true,
            contentType = GameContentType.GAME,
            // The sanitized intent, never the file's own text.
            launchIntentUri = sanitizedIntentUri,
            scrapedTitle = export.scrapedTitle,
            userTitleOverride = export.userTitleOverride,
            storefront = if (hasStorefront) export.storefront else null,
            storefrontGameId = if (hasStorefront) export.storefrontGameId else null,
            ssId = export.ssId,
            tgdbId = export.tgdbId,
            igdbId = export.igdbId,
            steamGridDbId = export.steamGridDbId,
        )
    }
}

/**
 * The artwork names the `.pfpgame` entries of one scan claim, for relink (C18 task X.5): each
 * `("windows", kind, portable name lowercased)` → the game it belongs to. A name two games claim is
 * claimed by neither, so relink falls back to its own matching instead of guessing.
 */
class PcGameArtworkClaims {
    private val owners = HashMap<Triple<String, String, String>, Long>()
    private val contested = HashSet<Triple<String, String, String>>()

    fun add(export: PcGameExport, gameId: Long) {
        export.artwork.forEach { item ->
            val key = Triple(WINDOWS_PLATFORM_ID, item.kind, item.portableName.lowercase())
            if (key in contested) return@forEach
            val owner = owners.putIfAbsent(key, gameId)
            if (owner != null && owner != gameId) {
                owners.remove(key)
                contested.add(key)
            }
        }
    }

    fun toMap(): Map<Triple<String, String, String>, Long> = owners.toMap()

    companion object {
        /**
         * The [claims] no artwork record fulfils yet: the claiming game has no record of that kind
         * under that name. Empty once relink has reconnected everything, so a later scan skips it.
         */
        fun unresolved(
            claims: Map<Triple<String, String, String>, Long>,
            recordsByGame: Map<Long, List<ArtworkRecordEntity>>,
        ): Map<Triple<String, String, String>, Long> = claims.filter { (key, gameId) ->
            recordsByGame[gameId].orEmpty().none { record ->
                record.artworkType == key.second && record.portableName.lowercase() == key.third
            }
        }
    }
}
