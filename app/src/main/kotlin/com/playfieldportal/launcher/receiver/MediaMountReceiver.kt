package com.playfieldportal.launcher.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.playfieldportal.feature.library.scanner.LibraryRescanCoordinator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The strong rescan signal: a card was just mounted, so ROMs added from a PC have only now become
 * readable. This is the moment they appear, so unlike the [onResume] path this bypasses the time
 * throttle (see docs/missing-roms-plan.md).
 *
 * Only ACTION_MEDIA_MOUNTED is handled. Unmount and friends are deliberately ignored — an
 * unmounted card is exactly the untrustworthy state the removal guard exists for, and diffing the
 * library against it would flag the whole card as missing.
 *
 * Registered at runtime by MainActivity (like [InstallShortcutReceiver]) rather than in the
 * manifest, both because Android 8+ blocks manifest registration for this implicit broadcast and
 * because rescanning is only useful while PFP is actually up.
 */
class MediaMountReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun libraryRescanCoordinator(): LibraryRescanCoordinator
    }

    // Deliberately NOT goAsync(): a broadcast's pending result must be finished within ~10s, but a
    // full SAF walk across every card can easily run longer, and the coordinator adds a 2s debounce
    // on top. Holding the broadcast open for that would risk the receiver being killed mid-scan.
    // This receiver only lives while MainActivity does, so the foreground activity is what keeps the
    // process alive for the duration — no pending result needed.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_MOUNTED) return

        Timber.i("Media mounted (${intent.data}) — requesting library rescan")
        val coordinator = EntryPointAccessors
            .fromApplication(context.applicationContext, Deps::class.java)
            .libraryRescanCoordinator()

        // The coordinator owns the debounce/single-flight guards, so the burst of broadcasts a
        // single mount produces collapses into one scan there rather than being filtered here.
        scope.launch {
            runCatching { coordinator.onMediaMounted() }
                .onFailure { Timber.e(it, "Mount-triggered library rescan failed") }
        }
    }
}
