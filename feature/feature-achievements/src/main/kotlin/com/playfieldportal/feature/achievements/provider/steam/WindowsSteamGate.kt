package com.playfieldportal.feature.achievements.provider.steam

import com.playfieldportal.core.domain.achievement.LocalCopyOwnership
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.WindowsGameLabel
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamOwnership
import com.playfieldportal.feature.artwork.match.Storefront
import com.playfieldportal.feature.artwork.match.StorefrontMetadataResolver
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether a Windows game's Steam app id may become a Steam achievement link.
 *
 * Steam only serves achievements for a game on the account. A game the user runs from a copy they
 * do not own has a Steam app id all the same — GameNative carries one, the title finds one — and a
 * Steam link made from it never syncs. That copy is a local one: its progress is in its own folder,
 * which is what its achievements page asks for.
 *
 * The owned-games list is the only evidence. An empty list (no Steam account connected, or never
 * fetched) proves nothing either way, so [Verdict.UNKNOWN] links nothing and the page asks the user.
 *
 * The user's own [WindowsGameLabel] on a game beats that evidence; the owned list decides only for an
 * unlabelled game.
 */
@Singleton
class WindowsSteamGate @Inject constructor(
    private val ownership: LocalSteamOwnership,
    private val storefronts: StorefrontMetadataResolver,
    private val games: GameRepository,
) {
    enum class Verdict { OWNED, LOCAL, UNKNOWN, NOT_STEAM }

    suspend fun verdict(appId: String): Verdict = when (ownership.derive(appId)) {
        LocalCopyOwnership.OWNED -> Verdict.OWNED
        LocalCopyOwnership.NOT_IN_LIBRARY -> Verdict.LOCAL
        null -> Verdict.UNKNOWN
    }

    /** [verdict] for [appId], unless the user labelled game [gameId] — then the label answers. */
    suspend fun steamVerdictFor(gameId: Long, appId: String): Verdict =
        when (games.getById(gameId)?.windowsLabel) {
            WindowsGameLabel.STEAM -> Verdict.OWNED
            WindowsGameLabel.LOCAL -> Verdict.LOCAL
            WindowsGameLabel.NON_STEAM -> Verdict.NOT_STEAM
            null -> verdict(appId)
        }

    /**
     * The Steam app id [game] already carries, without asking Steam: the one its shortcut launches
     * by, the one captured when it was imported, or the one Store Match linked.
     */
    suspend fun knownSteamAppId(game: Game): String? =
        SteamShortcut.appIdFrom(game)
            ?: game.storefrontGameId?.takeIf { game.storefront.equals(Storefront.STEAM.key, ignoreCase = true) }
            ?: runCatching { storefronts.linkedIdentities(game.id) }
                .onFailure { Timber.w(it, "Could not read storefront identities for game %d", game.id) }
                .getOrNull()
                ?.firstOrNull { it.store == Storefront.STEAM }
                ?.storeId

    /**
     * True when [game] is a local copy: labelled Local, or unlabelled with a known Steam app id that
     * is not in the user's populated owned list.
     */
    suspend fun isLocalCopy(game: Game): Boolean = when (game.windowsLabel) {
        WindowsGameLabel.LOCAL -> true
        WindowsGameLabel.STEAM, WindowsGameLabel.NON_STEAM -> false
        null -> knownSteamAppId(game)?.let { verdict(it) == Verdict.LOCAL } ?: false
    }

    /**
     * What PFP would guess for [game] ignoring its label, for display. Null when it can't tell; the
     * guess is never Non-Steam.
     */
    suspend fun autoLabel(game: Game): WindowsGameLabel? {
        val appId = knownSteamAppId(game) ?: return null
        return when (verdict(appId)) {
            Verdict.OWNED -> WindowsGameLabel.STEAM
            Verdict.LOCAL -> WindowsGameLabel.LOCAL
            Verdict.UNKNOWN, Verdict.NOT_STEAM -> null
        }
    }
}
