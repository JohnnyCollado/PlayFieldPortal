package com.playfieldportal.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.playfieldportal.core.ui.theme.PFPTheme
import com.playfieldportal.feature.library.scanner.LibraryRescanCoordinator
import com.playfieldportal.feature.xmb.gamepad.GamepadInputHandler
import com.playfieldportal.feature.xmb.viewmodel.XMBViewModel
import com.playfieldportal.launcher.discord.DiscordBootstrap
import com.playfieldportal.launcher.receiver.InstallShortcutReceiver
import com.playfieldportal.launcher.receiver.MediaMountReceiver
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var gamepadInputHandler: GamepadInputHandler

    @Inject
    lateinit var discordBootstrap: DiscordBootstrap

    @Inject
    lateinit var libraryRescanCoordinator: LibraryRescanCoordinator

    // Same activity-scoped instance the shell's hiltViewModel() resolves — used to report when
    // the notification-permission dialog is out of the way so the boot sequence can start.
    private val xmbViewModel: XMBViewModel by viewModels()

    // Runtime-registered so it actually fires on Android 8+ (manifest receivers are blocked for
    // this implicit broadcast). Lives for the activity's lifetime.
    private val installShortcutReceiver = InstallShortcutReceiver()

    // Also runtime-registered: ACTION_MEDIA_MOUNTED is an implicit broadcast, so a manifest entry
    // would never fire on Android 8+. Lives for the activity's lifetime.
    private val mediaMountReceiver = MediaMountReceiver()

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
        ContextCompat.registerReceiver(
            this,
            mediaMountReceiver,
            // The "file" data scheme is required — ACTION_MEDIA_MOUNTED carries a file:// URI for
            // the mounted volume, and a filter without a scheme never matches it.
            IntentFilter(Intent.ACTION_MEDIA_MOUNTED).apply { addDataScheme("file") },
            ContextCompat.RECEIVER_EXPORTED,
        )

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                //Left blank so that it can be ignored, preventing users from exiting the launcher.
                //Back is already handled by the gamepad input handler.
            }
        }

        onBackPressedDispatcher.addCallback(this, callback)

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
        // Same "back from a game" moment is the weak rescan signal: it catches ROMs downloaded or
        // deleted while PFP was backgrounded. The coordinator throttles this internally (5 min), so
        // calling it on every resume costs nothing when it fires in quick succession.
        lifecycleScope.launch {
            runCatching { libraryRescanCoordinator.onResume() }
                .onFailure { Timber.e(it, "Resume-triggered library rescan failed") }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(installShortcutReceiver) }
        runCatching { unregisterReceiver(mediaMountReceiver) }
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

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Let the gamepad handler process it first; fall back to normal dispatch
        if (gamepadInputHandler.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (gamepadInputHandler.onMotionEvent(event)) return true
        return super.onGenericMotionEvent(event)
    }
}
