package com.playfieldportal.launcher

import android.Manifest
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.playfieldportal.core.ui.theme.PFPTheme
import com.playfieldportal.launcher.discord.DiscordBootstrap
import com.playfieldportal.feature.xmb.gamepad.GamepadInputHandler
import com.playfieldportal.feature.xmb.ui.XMBShellContainer
import com.playfieldportal.feature.xmb.viewmodel.XMBViewModel
import com.playfieldportal.launcher.receiver.InstallShortcutReceiver
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var gamepadInputHandler: GamepadInputHandler
    @Inject lateinit var discordBootstrap: DiscordBootstrap

    // Same activity-scoped instance the shell's hiltViewModel() resolves — used to report when
    // the notification-permission dialog is out of the way so the boot sequence can start.
    private val xmbViewModel: XMBViewModel by viewModels()

    // Runtime-registered so it actually fires on Android 8+ (manifest receivers are blocked for
    // this implicit broadcast). Lives for the activity's lifetime.
    private val installShortcutReceiver = InstallShortcutReceiver()

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Best-effort grant; either way the dialog is resolved and startup can continue.
            xmbViewModel.onStartupPermissionsSettled()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        hideSystemBars()
        requestNotificationPermissionIfNeeded()
        ContextCompat.registerReceiver(
            this,
            installShortcutReceiver,
            IntentFilter(InstallShortcutReceiver.ACTION_INSTALL_SHORTCUT),
            ContextCompat.RECEIVER_EXPORTED,
        )

        // Discord bootstrap: attaches the SDK engine + restores a saved session in the full build,
        // or does nothing in the lite build (SDK excluded). Wired per flavor via Hilt.
        discordBootstrap.onCreate(this)

        setContent {
            PFPTheme {
                // AppXmbHost is defined per build variant: the debug source set wraps the shell so
                // long-pressing Settings opens DebugMenuScreen; the release source set calls
                // XMBShellContainer directly, keeping debug code out of the APK.
                AppXmbHost()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // Foreground again = out of any game, so drop the per-game Discord presence back to idle
        // (full build only; no-op in lite). Cheap unless a game was actually being shared.
        discordBootstrap.onResume()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(installShortcutReceiver) }
        super.onDestroy()
    }

    // Background-task notifications need the POST_NOTIFICATIONS runtime grant on API 33+.
    // Every early-return path reports the permission flow settled so the boot sequence
    // (which holds until then) can start.
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            xmbViewModel.onStartupPermissionsSettled()
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            xmbViewModel.onStartupPermissionsSettled()
            return
        }
        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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
