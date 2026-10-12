package com.playfieldportal.feature.launcher

import android.content.Context
import android.content.Intent
import com.playfieldportal.core.data.database.dao.GameStorefrontIdentityDao
import com.playfieldportal.core.domain.model.Game
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** One launcher a Windows game can run with, as Launch With lists it. */
data class PcLaunchOption(val packageName: String, val label: String, val isOriginal: Boolean)

/** A store's id for a game (STEAM 1173820, GOG 1207658924, …): what id-based launchers address. */
data class PcStoreId(val store: String, val id: String)

/**
 * Which installed launchers can open a Windows game. The launcher a game was imported from always
 * can — its own shortcut or intent is the handle. Another launcher only can when it addresses
 * games by a store id this game has (GameNative by any of its stores, the GameHub family by Steam
 * appid), or — for a Winlator fork — when the game is a `.desktop` export on shared storage, which
 * every fork can open ([WinlatorDesktop]). A pinned Winlator shortcut lives inside its own app.
 */
object PcLaunchRouting {

    private val GAMENATIVE_STORES = setOf("STEAM", "EPIC", "GOG", "AMAZON")

    /** The id [type] would launch the game by, or null when it cannot address it from [ids]. */
    internal fun storeIdFor(type: PcLauncherType, ids: List<PcStoreId>): PcStoreId? {
        val usable = ids.filter { (it.id.toIntOrNull() ?: 0) > 0 }
        return when {
            type == PcLauncherType.GAMENATIVE -> usable.firstOrNull { it.store in GAMENATIVE_STORES }
            type.isGameHubFamily -> usable.firstOrNull { it.store == STEAM }
            else -> null
        }
    }

    /** The game's own launcher first, then every other installed launcher that can open it. */
    internal fun options(
        ownPackage: String,
        ownLabel: String,
        installed: List<InstalledPcLauncher>,
        ids: List<PcStoreId>,
        sharedDesktop: Boolean = false,
    ): List<PcLaunchOption> =
        listOf(PcLaunchOption(ownPackage, ownLabel, isOriginal = true)) +
            installed
                .filter { it.packageName != ownPackage && canOpen(it.type, ids, sharedDesktop) }
                .map { PcLaunchOption(it.packageName, it.label, isOriginal = false) }

    private fun canOpen(type: PcLauncherType, ids: List<PcStoreId>, sharedDesktop: Boolean): Boolean =
        storeIdFor(type, ids) != null || (sharedDesktop && type == PcLauncherType.WINLATOR)

    private const val STEAM = "STEAM"
}

/** [PcLaunchRouting] against the installed launchers and the game's known store ids. */
@Singleton
class PcLaunchRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val launcherChoices: LauncherChoiceRepository,
    private val identities: GameStorefrontIdentityDao,
) {
    /** Launch With's rows for [game]; empty when it has no launcher of its own. */
    suspend fun options(game: Game): List<PcLaunchOption> {
        val own = game.packageName ?: return emptyList()
        val pm = context.packageManager
        return PcLaunchRouting.options(
            ownPackage = own,
            ownLabel = PcLauncherCatalog.displayName(own, pm),
            installed = installed(),
            ids = storeIds(game),
            sharedDesktop = sharedDesktopPath(game) != null,
        )
    }

    /** The name Launch With shows for the launcher [game] runs with now. */
    fun currentLabel(game: Game): String? =
        (game.pcLauncherPackage ?: game.packageName)?.let { PcLauncherCatalog.displayName(it, context.packageManager) }

    /**
     * The intent that opens [game] in the launcher the user chose, or null to launch it through its
     * own handle — no choice made, or the chosen launcher is gone or can no longer address it.
     */
    suspend fun chosenLaunchIntent(game: Game): Intent? {
        val chosen = game.pcLauncherPackage?.takeIf { it != game.packageName } ?: return null
        val pm = context.packageManager
        val launcher = installed().firstOrNull { it.packageName == chosen }
        val storeId = launcher?.let { PcLaunchRouting.storeIdFor(it.type, storeIds(game)) }
        val desktopPath = sharedDesktopPath(game)?.takeIf { launcher?.type == PcLauncherType.WINLATOR }
        val intent = when {
            launcher == null -> null
            storeId != null ->
                PcLauncherAdapters.forType(launcher.type, pm)?.buildLaunchIntent(chosen, storeId.id, storeId.store)
            desktopPath != null -> WinlatorDesktop.launchIntent(chosen, desktopPath, pm)
            else -> null
        }
        if (intent == null) Timber.w("Launch With: $chosen can't open \"${game.title}\" now; using its own launcher")
        return intent
    }

    // A .desktop export on shared storage, which any installed Winlator fork can open.
    private fun sharedDesktopPath(game: Game): String? =
        WinlatorDesktop.shortcutPathOf(game.launchIntentUri)?.takeIf(WinlatorDesktop::isShared)

    private suspend fun installed(): List<InstalledPcLauncher> =
        PcLauncherCatalog.installedLaunchers(context.packageManager, launcherChoices.snapshot())

    // The import identity first (what the game was installed as), then identities resolved later.
    private suspend fun storeIds(game: Game): List<PcStoreId> = buildList {
        val store = game.storefront
        val storeId = game.storefrontGameId
        if (store != null && storeId != null) add(PcStoreId(store, storeId))
        identities.forGame(game.id).forEach { add(PcStoreId(it.store, it.storeId)) }
    }
}
