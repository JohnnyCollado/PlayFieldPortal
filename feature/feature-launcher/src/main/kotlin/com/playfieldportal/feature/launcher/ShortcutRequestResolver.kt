package com.playfieldportal.feature.launcher

import com.playfieldportal.core.data.repository.CollectionRepository
import com.playfieldportal.core.data.repository.PendingShortcutRequest
import com.playfieldportal.core.data.repository.PendingShortcutRequestStore
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameContentType
import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationKind
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.core.ui.notification.BackgroundTaskCenter
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves legacy INSTALL_SHORTCUT requests inside the launcher.
 *
 * The confirmation used to be Add / Ignore buttons on an Android shade notification. PFP's
 * notifications are launcher-only now (the notification details plan §9.3): a request waits in
 * [PendingShortcutRequestStore] with an unread tray row, and the launcher asks in its own modal.
 * Nothing here can be triggered by the sending app — only the user's choice in PFP's UI calls
 * [add] — which is the same guarantee the unexported confirm receiver gave.
 */
@Singleton
class ShortcutRequestResolver @Inject constructor(
    private val store: PendingShortcutRequestStore,
    private val gameRepository: GameRepository,
    private val collectionRepository: CollectionRepository,
    private val pcShortcutImporter: PcShortcutImporter,
    private val tasks: BackgroundTaskCenter,
) {
    val requests = store.requests

    suspend fun get(id: String): PendingShortcutRequest? = store.get(id)

    /** Queues [request] and posts its unread row, which opens the review when selected. */
    suspend fun enqueue(request: PendingShortcutRequest) {
        store.enqueue(request)
        tasks.report(
            id = rowId(request.id),
            // hostLabel is the app the shortcut OPENS, not the sender: the INSTALL_SHORTCUT
            // broadcast does not say who sent it, so the copy must not attribute it.
            label = "Shortcut request: ${request.name} (opens ${request.hostLabel})",
            message = "Only add it if you just made this shortcut yourself.",
            severity = NotificationSeverity.INFO,
            kind = NotificationKind.SYSTEM,
            action = NotificationAction.ReviewShortcut(request.id),
            read = false,
        )
    }

    /**
     * The user chose Add on [shown]: file the shortcut, then settle its row as read. Nothing is
     * filed unless the queued request is still exactly [shown] — one replaced under the same id
     * while the review was open stays queued and is asked about on its own.
     */
    suspend fun add(shown: PendingShortcutRequest) {
        val request = store.take(shown) ?: run {
            Timber.w("Shortcut request ${shown.id} changed or went away during review; not added")
            return
        }
        val id = request.id
        val hostPackage = request.hostPackage
        // A confirmed shortcut from a verified PC launcher is a Windows game
        // (docs/windows-library-refactor-plan.md section 3); anything else keeps the app-style
        // collection entry.
        if (pcShortcutImporter.isPcLauncher(hostPackage) && hostPackage != null) {
            val result = pcShortcutImporter.importLegacyShortcut(hostPackage, request.name, request.intentUri)
            if (result.needsSetup) windowsSetupNeeded(request.name)
            Timber.i("User confirmed PC shortcut \"${request.name}\" → Windows Games")
        } else {
            val gameId = gameRepository.getByIntentUri(request.intentUri)?.id
                ?: gameRepository.upsert(
                    Game(
                        title = request.name,
                        platformId = ANDROID_PLATFORM_ID,
                        packageName = hostPackage,
                        isManualEntry = true,
                        contentType = GameContentType.SHORTCUT,
                        launchIntentUri = request.intentUri,
                    )
                )
            val collectionId = collectionRepository.getAll().firstOrNull { it.name == request.hostLabel }?.id
                ?: collectionRepository.create(request.hostLabel)
            collectionRepository.addGame(collectionId, gameId)
            Timber.i("User confirmed shortcut \"${request.name}\" → collection \"${request.hostLabel}\"")
        }
        tasks.report(
            id = rowId(id),
            label = "Added shortcut: ${request.name}",
            message = "From ${request.hostLabel}",
            severity = NotificationSeverity.SUCCESS,
            kind = NotificationKind.SYSTEM,
            read = true,
        )
    }

    /** The user chose Ignore: drop the request and settle its row as read. */
    suspend fun ignore(id: String) {
        val request = store.get(id) ?: return
        store.remove(id)
        tasks.report(
            id = rowId(id),
            label = "Ignored shortcut: ${request.name}",
            message = "From ${request.hostLabel}",
            severity = NotificationSeverity.INFO,
            kind = NotificationKind.SYSTEM,
            read = true,
        )
    }

    /**
     * A PC game arrived before the Windows Library has a folder. Replaces the shade's "finish
     * setting up" notification; the one-time XMB prompt still backs it up.
     */
    fun windowsSetupNeeded(gameTitle: String) {
        tasks.report(
            id = "windows_setup",
            label = "\"$gameTitle\" added to Windows Games",
            message = "Finish setting up your Windows Library to scan its folder",
            severity = NotificationSeverity.INFO,
            kind = NotificationKind.SYSTEM,
            action = NotificationAction.OpenSettingsScreen(WINDOWS_SETUP_ROUTE),
        )
    }

    private fun rowId(id: String) = "shortcut:$id"

    private companion object {
        const val ANDROID_PLATFORM_ID = "android"
        /** Library Manager: where the XMB's own Set Up prompt sends the user too. */
        const val WINDOWS_SETUP_ROUTE = "settings_library"
    }
}
