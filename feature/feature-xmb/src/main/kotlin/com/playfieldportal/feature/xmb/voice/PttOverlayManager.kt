package com.playfieldportal.feature.xmb.voice

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.playfieldportal.core.data.discord.DiscordVoiceController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The floating push-to-talk button. It's a system overlay (TYPE_APPLICATION_OVERLAY) so it stays on
 * top of a running game — the only place a talk control is reachable while PFP is backgrounded. Hold
 * to open the mic, release to close it; drag to move it out of the way. Needs the "Draw over other
 * apps" grant ([canDraw]); the process stays alive during a call via the SDK's mic foreground service,
 * so the window persists without a service of our own.
 */
@Singleton
class PttOverlayManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val voice: DiscordVoiceController,
) {
    private val windowManager get() = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var overlay: View? = null

    /** Whether the "Draw over other apps" permission is granted. */
    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    val isShowing: Boolean get() = overlay != null

    /** Show the hold-to-talk button. No-op without permission or if already shown. */
    fun show() {
        if (overlay != null || !canDraw()) return
        val button = TextView(context).apply {
            text = "🎙  HOLD"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(44, 28, 44, 28)
            alpha = 0.85f
            background = GradientDrawable().apply {
                cornerRadius = 64f
                setColor(0xCC5865F2.toInt())   // Discord blurple, translucent
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 64
            y = 180
        }
        button.setOnTouchListener(holdAndDrag(params, button))
        runCatching { windowManager.addView(button, params) }.onSuccess { overlay = button }
    }

    /** Remove the button and make sure the mic isn't left open. */
    fun hide() {
        overlay?.let { v -> runCatching { windowManager.removeView(v) } }
        overlay = null
        scope.launch { voice.setPttActive(false) }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

    // Press-and-hold opens the mic; a drag past a small threshold repositions instead of transmitting
    // (so nudging the button doesn't hot-mic). Gravity is BOTTOM|END, so x grows left and y grows up.
    private fun holdAndDrag(params: WindowManager.LayoutParams, view: View): View.OnTouchListener {
        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        var dragging = false
        return View.OnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    downRawX = e.rawX; downRawY = e.rawY
                    dragging = false
                    v.alpha = 1f
                    scope.launch { voice.setPttActive(true) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragging && (abs(dx) > DRAG_THRESHOLD || abs(dy) > DRAG_THRESHOLD)) {
                        dragging = true
                        scope.launch { voice.setPttActive(false) }   // repositioning, not talking
                    }
                    if (dragging) {
                        params.x = (startX - dx).toInt().coerceAtLeast(0)
                        params.y = (startY - dy).toInt().coerceAtLeast(0)
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 0.85f
                    scope.launch { voice.setPttActive(false) }
                    true
                }
                else -> false
            }
        }
    }

    private companion object {
        const val DRAG_THRESHOLD = 28f
    }
}
