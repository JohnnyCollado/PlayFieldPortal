package com.playfieldportal.launcher.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.playfieldportal.feature.library.scanner.LibraryRescanCoordinator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber

/**
 * Rescan trigger for the "copied ROMs over the USB cable, then unplugged" case.
 *
 * [MediaMountReceiver] does NOT cover this: a PC transfer goes over MTP, and MTP never unmounts the
 * storage volume — Android keeps it mounted and lets the PC read/write through MediaProvider. So
 * unplugging fires no ACTION_MEDIA_MOUNTED (nothing was ever unmounted to re-mount). ACTION_MEDIA_
 * MOUNTED only fires for physically inserted removable media (an SD card, USB-OTG storage).
 *
 * ACTION_USB_STATE is the signal that actually marks the unplug moment: its `connected` extra flips
 * to false when the cable is pulled. Firing the strong rescan on that connected -> disconnected edge
 * catches the freshly-copied ROMs right when the user is back on the device.
 *
 * Routed to [LibraryRescanCoordinator.onMediaMounted] — the same strong-signal path the mount
 * receiver uses (throttle-bypassing, debounced, single-flight), so a mount and an unplug arriving
 * together collapse into one scan rather than two.
 *
 * ACTION_USB_STATE and its `connected` extra are @hide in the public SDK, so the stable platform
 * string values are used directly; they are a fixed contract the USB stack has emitted for years.
 */
class UsbDisconnectReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun libraryRescanCoordinator(): LibraryRescanCoordinator
    }


    // Last seen USB connection state. Null until the first broadcast so the sticky delivery that
    // arrives immediately on registration only RECORDS the current state — it must not be mistaken
    // for a fresh unplug and trigger a scan on every app start.
    private var lastConnected: Boolean? = null

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_USB_STATE) return

        val connected = intent.getBooleanExtra(EXTRA_CONNECTED, false)
        val was = lastConnected
        lastConnected = connected

        // Only a genuine connected -> disconnected edge is an unplug. The first sticky delivery
        // (was == null) just seeds the state; a disconnected -> disconnected repeat is ignored.
        if (was != true || connected) return

        Timber.i("USB disconnected — requesting library rescan")
        EntryPointAccessors
            .fromApplication(context.applicationContext, Deps::class.java)
            .libraryRescanCoordinator()
            .onMediaMounted()
    }

    companion object {
        // UsbManager.ACTION_USB_STATE / UsbManager.USB_CONNECTED — both @hide, inlined as literals.
        const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
        private const val EXTRA_CONNECTED = "connected"
    }
}
