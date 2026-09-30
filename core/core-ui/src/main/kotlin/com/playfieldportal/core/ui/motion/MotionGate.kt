package com.playfieldportal.core.ui.motion

import com.playfieldportal.core.domain.model.ImageMotion
import java.lang.ref.WeakReference

/**
 * One image's answer to "may my animation play right now?" — [ImageMotion.shouldAnimate] over the
 * setting, the image's focus, whether PFP may animate at all, and whether the image is on screen.
 *
 * Each [ArtworkImage] remembers its own gate and rides it into its Coil request; the image
 * loader's interceptor hands it to the animated drawable it decodes, which starts and stops as the
 * gate opens and closes. The request itself never changes when focus moves, so nothing reloads:
 * a still image is untouched, and an animated one just pauses or resumes.
 *
 * Inputs are set on the main thread (composition and layout). Listeners may be added from the
 * loader's background thread, so the list is synchronized; it holds them weakly so a drawable
 * that has been replaced is not kept alive by the gate that fed it.
 */
class MotionGate private constructor(tracksPosition: Boolean) {

    /** A new gate for one on-screen image: closed until [onScreen] is first reported. */
    constructor() : this(tracksPosition = true)

    fun interface Listener {
        fun onAnimateChanged(animate: Boolean)
    }

    private var mode = ImageMotion.DEFAULT
    private var focused = false
    private var allowed = false
    private val listeners = mutableListOf<WeakReference<Listener>>()

    /** Whether the image is inside the window. Reported by [motionOnScreen]. */
    var onScreen: Boolean = !tracksPosition
        set(value) {
            if (field == value) return
            field = value
            recompute()
        }

    /** The current answer. */
    @Volatile
    var animate: Boolean = false
        private set

    fun update(mode: ImageMotion, focused: Boolean, allowed: Boolean) {
        this.mode = mode
        this.focused = focused
        this.allowed = allowed
        recompute()
    }

    fun addListener(listener: Listener) = synchronized(listeners) {
        listeners += WeakReference(listener)
    }

    fun removeListener(listener: Listener) = synchronized(listeners) {
        listeners.removeAll { it.get().let { held -> held == null || held === listener } }
    }

    private fun recompute() {
        val next = mode.shouldAnimate(onScreen = onScreen, focused = focused, allowed = allowed)
        if (next == animate) return
        animate = next
        val live = synchronized(listeners) {
            listeners.removeAll { it.get() == null }
            listeners.mapNotNull { it.get() }
        }
        live.forEach { it.onAnimateChanged(next) }
    }

    companion object {
        /**
         * A gate for an image drawn without [ArtworkImage] (a settings preview, a Studio result
         * tile): nothing tracks its position or focus, so it counts as both, and only the setting
         * and "allowed" decide. [Shared] is the one every such image uses.
         */
        fun unmanaged(): MotionGate = MotionGate(tracksPosition = false)

        /** Updated from the app root. See [unmanaged]. */
        val Shared: MotionGate = unmanaged()
    }
}
