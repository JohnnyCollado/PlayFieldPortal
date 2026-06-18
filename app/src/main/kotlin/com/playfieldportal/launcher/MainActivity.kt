package com.playfieldportal.launcher

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.playfieldportal.core.ui.theme.PFPTheme
import com.playfieldportal.feature.xmb.gamepad.GamepadInputHandler
import com.playfieldportal.feature.xmb.ui.XMBShellContainer
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var gamepadInputHandler: GamepadInputHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        hideSystemBars()

        setContent {
            PFPTheme {
                // Debug builds wrap the shell so long-pressing Settings opens DebugMenuScreen.
                // Release builds call XMBShellContainer directly — no debug code in the APK.
                if (BuildConfig.DEBUG) {
                    com.playfieldportal.launcher.debug.DebugAwareXMBHost { onSettingsLongPress ->
                        XMBShellContainer(onSettingsLongPress = onSettingsLongPress)
                    }
                } else {
                    XMBShellContainer()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ── Controller input forwarding ───────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Let the gamepad handler process it first; fall back to normal dispatch
        if (gamepadInputHandler.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (gamepadInputHandler.onMotionEvent(event)) return true
        return super.onGenericMotionEvent(event)
    }

    // Intercept back press — prevent leaving the launcher accidentally
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Gamepad BACK action is handled in XMBViewModel via GamepadInputHandler.
        // Physical back key on devices without a gamepad is suppressed here —
        // users navigate back using the B/Circle button or on-screen back button.
    }
}
