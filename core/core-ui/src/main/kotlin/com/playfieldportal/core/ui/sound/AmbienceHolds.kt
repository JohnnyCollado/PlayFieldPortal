package com.playfieldportal.core.ui.sound

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The one thing other features need from [AmbienceController]: hold the background music down
 * while they own the room. An interface so a feature's tests can hand in a plain [AmbienceHolds]
 * without building the real controller (which owns an ExoPlayer and audio focus).
 */
interface AmbienceSuppressor {
    /**
     * Holds ambience down until the returned [AmbienceHold] is released. [owner] (one of
     * AmbienceController's OWNER_* names) only labels the hold: every call is its own hold, so two
     * holders can never release each other, even under the same name.
     */
    fun hold(owner: String): AmbienceHold
}

/** One hold on the background music. [release] ends it; releasing again does nothing. */
class AmbienceHold internal constructor(
    val owner: String,
    private val onRelease: (AmbienceHold) -> Unit,
) {
    private var released = false

    fun release() {
        if (released) return
        released = true
        onRelease(this)
    }
}

/**
 * The set of live [AmbienceHold]s — pure state, so the rule "owners never release one another" is
 * pinned by plain JVM tests rather than by the ExoPlayer-owning [AmbienceController] that reads it.
 */
class AmbienceHolds : AmbienceSuppressor {
    private val live = MutableStateFlow<List<AmbienceHold>>(emptyList())
    private val heldFlow = MutableStateFlow(false)

    /** True while any hold is live; the controller's suppression gate collects it. */
    val held: StateFlow<Boolean> = heldFlow.asStateFlow()

    val isHeld: Boolean get() = live.value.isNotEmpty()

    /** The owner names of the live holds, oldest first (a name repeats when two holds share it). */
    val owners: List<String> get() = live.value.map { it.owner }

    override fun hold(owner: String): AmbienceHold {
        val hold = AmbienceHold(owner) { done ->
            live.update { holds -> holds.filterNot { it === done } }
            heldFlow.value = live.value.isNotEmpty()
        }
        live.update { it + hold }
        heldFlow.value = true
        return hold
    }
}
