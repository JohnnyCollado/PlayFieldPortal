package com.playfieldportal.feature.achievements.provider.steam

import com.playfieldportal.core.domain.achievement.LocalCopyOwnership
import com.playfieldportal.core.domain.model.Game
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
 */
@Singleton
class WindowsSteamGate @Inject constructor(
    private val ownership: LocalSteamOwnership,
    private val storefronts: StorefrontMetadataResolver,
) {
    enum class Verdict { OWNED, LOCAL, UNKNOWN }

    suspend fun verdict(appId: String): Verdict = when (ownership.derive(appId)) {
        LocalCopyOwnership.OWNED -> Verdict.OWNED
        LocalCopyOwnership.NOT_IN_LIBRARY -> Verdict.LOCAL
        null -> Verdict.UNKNOWN
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

    /** True when [game]'s Steam app id is known and not in the user's populated owned list. */
    suspend fun isLocalCopy(game: Game): Boolean {
        val appId = knownSteamAppId(game) ?: return false
        return verdict(appId) == Verdict.LOCAL
    }
}
