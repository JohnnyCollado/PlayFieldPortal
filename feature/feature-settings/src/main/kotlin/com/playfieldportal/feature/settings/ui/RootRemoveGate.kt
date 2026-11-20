package com.playfieldportal.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.playfieldportal.core.ui.components.PfpModalSpec

/**
 * Asks before a root folder is removed.
 *
 * A root's Remove button used to act on the press. Removing a root takes its games or media out of
 * the library, and the cursor now lands straight on a root row's buttons, so the press goes through
 * this gate instead: [request] holds the removal, [spec] is the question, and only its Confirm runs
 * it. One gate per screen; a screen shows [spec] through its modal host.
 */
internal class RootRemoveGate {

    private class Pending(val name: String, val remove: () -> Unit)

    private var pending by mutableStateOf<Pending?>(null)

    /** Remove was pressed on the folder shown as [name]; [remove] runs only if the user confirms. */
    fun request(name: String, remove: () -> Unit) {
        pending = Pending(name, remove)
    }

    /** The open question, or null when nothing is being asked. */
    val spec: PfpModalSpec.Confirm?
        get() = pending?.let { asked ->
            PfpModalSpec.Confirm(
                key = "remove_root:${asked.name}",
                title = "Remove this folder?",
                message = "PFP will stop using \"${asked.name}\", and what it found there leaves the library. " +
                    "No files are deleted from your storage.",
                confirmLabel = "Remove",
                // Destructive: red Confirm, and the modal opens on Cancel so a double press is harmless.
                destructive = true,
                onConfirm = { pending = null; asked.remove() },
                onCancel = { pending = null },
            )
        }
}

@Composable
internal fun rememberRootRemoveGate(): RootRemoveGate = remember { RootRemoveGate() }
