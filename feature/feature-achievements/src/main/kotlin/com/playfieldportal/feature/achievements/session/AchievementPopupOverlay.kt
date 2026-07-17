package com.playfieldportal.feature.achievements.session

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** One unlock as the popup renders it. [tier] is a ShibaTier enum name ("BRONZE".."PLATINUM"). */
data class AchievementPopup(
    val title: String,
    val description: String,
    val tier: String,
)

/**
 * The in-game achievement banner: a system overlay (TYPE_APPLICATION_OVERLAY, same grant as the
 * Discord PTT button) that slides in top-center over the running game when a coin is earned,
 * holds a few seconds, and slides out. Unlocks queue — bursts show one after another, one window
 * at a time. Not touchable and not focusable, so game input is never intercepted; everything
 * no-ops without the "Draw over other apps" grant.
 */
@Singleton
class AchievementPopupOverlay @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val windowManager get() = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private val queue = ArrayDeque<AchievementPopup>()
    private var showing: View? = null

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    fun enqueue(popup: AchievementPopup) {
        if (!canDraw()) return
        handler.post {
            queue.addLast(popup)
            drain()
        }
    }

    /** Tears down any visible banner and drops the queue (session ended). */
    fun clear() {
        handler.post {
            queue.clear()
            showing?.let { v -> runCatching { windowManager.removeView(v) } }
            showing = null
        }
    }

    // Main-thread only. One banner at a time; the next starts when this one finishes leaving.
    private fun drain() {
        if (showing != null) return
        val popup = queue.removeFirstOrNull() ?: return
        val view = buildBanner(popup)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 64
        }
        if (runCatching { windowManager.addView(view, lp) }.isFailure) {
            drain()   // couldn't add (grant revoked mid-session) — try the next / give up
            return
        }
        showing = view
        view.alpha = 0f
        view.translationY = -40f
        view.animate().alpha(1f).translationY(0f).setDuration(220).start()
        handler.postDelayed({ dismiss(view) }, VISIBLE_MS)
    }

    private fun dismiss(view: View) {
        view.animate().alpha(0f).translationY(-40f).setDuration(200).withEndAction {
            runCatching { windowManager.removeView(view) }
            if (showing === view) showing = null
            drain()
        }.start()
    }

    private fun buildBanner(popup: AchievementPopup): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xF014141F.toInt())
                setStroke(dp(1), tierColor(popup.tier))
            }
        }
        // Tier medal dot in the coin's metal color.
        root.addView(
            TextView(context).apply {
                text = "●"
                setTextColor(tierColor(popup.tier))
                textSize = 20f
            },
        )
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        texts.addView(
            TextView(context).apply {
                text = popup.title
                setTextColor(0xFFEEEEEE.toInt())
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = dp(320)
            },
        )
        if (popup.description.isNotBlank()) {
            texts.addView(
                TextView(context).apply {
                    text = popup.description
                    setTextColor(0x99EEEEEE.toInt())
                    textSize = 12f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    maxWidth = dp(320)
                },
            )
        }
        root.addView(texts)
        return root
    }

    // Same metal palette as the Shiba Coins screens.
    private fun tierColor(tier: String): Int = when (tier) {
        "SILVER"   -> 0xFFB9C0C7.toInt()
        "GOLD"     -> 0xFFE1B12C.toInt()
        "PLATINUM" -> 0xFF6F9BF5.toInt()
        else       -> 0xFFC07C46.toInt()   // BRONZE / unknown
    }

    private companion object {
        const val VISIBLE_MS = 4_500L
    }
}
