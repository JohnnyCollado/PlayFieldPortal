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
 * top of a running game — the only place a talk control is reachable while PFP is backgrounded. Needs
 * the "Draw over other apps" grant ([canDraw]); the process stays alive during a call via the SDK's
 * mic foreground service, so the window persists without a service of our own.
 *
 * Two states: **expanded** — a hold-to-talk pill (hold = mic open, release = closed) you can drag;
 * dragging it against an edge **collapses** it to a small chevron tab docked there, out of the way.
 * Tapping the tab expands it again.
 */
@Singleton
class PttOverlayManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val voice: DiscordVoiceController,
) {
    private val windowManager get() = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var collapsed = false
    private var dockRight = true   // which edge the collapsed tab clings to

    /** Whether the "Draw over other apps" permission is granted. */
    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    val isShowing: Boolean get() = view != null

    /** Show the talk button (expanded). No-op without permission or if already shown. */
    fun show() {
        if (view != null || !canDraw()) return
        val tv = TextView(context)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW() * 0.72f).toInt()
            y = (screenH() * 0.62f).toInt()
        }
        collapsed = false
        styleExpanded(tv)
        tv.setOnTouchListener(touchListener())
        runCatching { windowManager.addView(tv, lp) }.onSuccess {
            view = tv
            params = lp
        }
    }

    /** Remove the button and make sure the mic isn't left open. */
    fun hide() {
        view?.let { v -> runCatching { windowManager.removeView(v) } }
        view = null
        params = null
        scope.launch { voice.setPttActive(false) }
    }

    // ── Styling ─────────────────────────────────────────────────────────────────────
    private fun styleExpanded(tv: TextView) {
        tv.text = "🎙  HOLD"
        tv.setTextColor(0xFFFFFFFF.toInt())
        tv.textSize = 15f
        tv.gravity = Gravity.CENTER
        tv.setPadding(44, 28, 44, 28)
        tv.alpha = 0.85f
        tv.background = GradientDrawable().apply {
            cornerRadius = 64f
            setColor(0xCC5865F2.toInt())   // Discord blurple, translucent
        }
    }

    private fun styleCollapsed(tv: TextView) {
        tv.text = if (dockRight) "‹" else "›"   // chevron points toward where it expands
        tv.setTextColor(0xFFFFFFFF.toInt())
        tv.textSize = 22f
        tv.gravity = Gravity.CENTER
        tv.setPadding(18, 26, 18, 26)
        tv.alpha = 0.75f
        // Rounded only on the inner side so it reads as a tab clinging to the edge.
        val r = 48f
        val radii = if (dockRight) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        tv.background = GradientDrawable().apply {
            cornerRadii = radii
            setColor(0xCC5865F2.toInt())
        }
    }

    // ── State transitions ───────────────────────────────────────────────────────────
    private fun collapse(toRight: Boolean) {
        val tv = view ?: return
        val lp = params ?: return
        collapsed = true
        dockRight = toRight
        scope.launch { voice.setPttActive(false) }
        styleCollapsed(tv)
        lp.gravity = Gravity.TOP or (if (toRight) Gravity.END else Gravity.START)
        lp.x = 0   // flush to the edge via gravity
        runCatching { windowManager.updateViewLayout(tv, lp) }
    }

    private fun expand() {
        val tv = view ?: return
        val lp = params ?: return
        collapsed = false
        styleExpanded(tv)
        lp.gravity = Gravity.TOP or Gravity.START
        // Re-anchor just inside the edge it was docked to; keep its vertical position.
        lp.x = if (dockRight) (screenW() * 0.72f).toInt() else (screenW() * 0.06f).toInt()
        runCatching { windowManager.updateViewLayout(tv, lp) }
    }

    // ── Touch ───────────────────────────────────────────────────────────────────────
    // Expanded: hold = talk, drag = move, release near an edge = collapse. Collapsed: tap = expand.
    private fun touchListener(): View.OnTouchListener {
        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        var dragging = false
        return View.OnTouchListener { v, e ->
            val lp = params ?: return@OnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    downRawX = e.rawX; downRawY = e.rawY
                    dragging = false
                    if (!collapsed) {
                        v.alpha = 1f
                        scope.launch { voice.setPttActive(true) }
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragging && (abs(dx) > DRAG_THRESHOLD || abs(dy) > DRAG_THRESHOLD)) {
                        dragging = true
                        if (!collapsed) scope.launch { voice.setPttActive(false) }  // moving, not talking
                    }
                    // Only reposition freely while expanded; the collapsed tab stays pinned to its edge.
                    if (dragging && !collapsed) {
                        lp.x = (startX + dx).toInt().coerceIn(0, (screenW() - v.width).coerceAtLeast(0))
                        lp.y = (startY + dy).toInt().coerceIn(0, (screenH() - v.height).coerceAtLeast(0))
                        runCatching { windowManager.updateViewLayout(v, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (collapsed) {
                        if (!dragging) expand()   // a tap on the tab
                    } else {
                        v.alpha = 0.85f
                        scope.launch { voice.setPttActive(false) }
                        if (dragging) {
                            val right = lp.x + v.width
                            when {
                                lp.x <= EDGE_SNAP -> collapse(toRight = false)
                                right >= screenW() - EDGE_SNAP -> collapse(toRight = true)
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun screenW() = context.resources.displayMetrics.widthPixels
    private fun screenH() = context.resources.displayMetrics.heightPixels

    private companion object {
        const val DRAG_THRESHOLD = 28f
        const val EDGE_SNAP = 72   // px from an edge that counts as "docked"
    }
}
