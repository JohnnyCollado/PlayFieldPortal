package com.playfieldportal.launcher

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.playfieldportal.core.domain.model.ScreenOrientationMode
import com.playfieldportal.core.ui.orientation.RotatePromptKey
import com.playfieldportal.core.ui.orientation.RotateToLandscapeScreen
import com.playfieldportal.core.ui.orientation.requestedOrientationFor
import com.playfieldportal.core.ui.orientation.rotatePromptKey
import com.playfieldportal.core.ui.orientation.showRotatePrompt
import com.playfieldportal.core.ui.sound.LocalMenuSounds
import com.playfieldportal.core.ui.sound.MenuSoundSink
import com.playfieldportal.core.ui.theme.PFPTheme
import com.playfieldportal.feature.library.scanner.LibraryRescanCoordinator
import com.playfieldportal.feature.settings.media.MediaRescanCoordinator
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import com.playfieldportal.feature.xmb.gamepad.GamepadInputHandler
import com.playfieldportal.feature.xmb.viewmodel.XMBViewModel
import com.playfieldportal.launcher.discord.DiscordBootstrap
import com.playfieldportal.launcher.receiver.InstallShortcutReceiver
import com.playfieldportal.launcher.receiver.MediaMountReceiver
import com.playfieldportal.launcher.receiver.UsbDisconnectReceiver
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var gamepadInputHandler: GamepadInputHandler

    @Inject
    lateinit var discordBootstrap: DiscordBootstrap

    @Inject
    lateinit var libraryRescanCoordinator: LibraryRescanCoordinator

    @Inject
    lateinit var mediaRescanCoordinator: MediaRescanCoordinator

    // Owns the user's ui-media assignments; a cold start prunes anything left by slots the
    // current build no longer has (removed sound slots, crashed-import staging files).
    @Inject
    lateinit var uiMediaStore: com.playfieldportal.core.data.repository.UiMediaStore

    // B1 launch verification: the home-launcher handshake. A dispatched game launch is only
    // "real" if another activity covers this launcher (onStop) and the user comes back after a
    // real session (onResume). Nothing else in the app reports lifecycle to the dispatcher.
    @Inject
    lateinit var launchDispatcher: com.playfieldportal.feature.launcher.LaunchDispatcher

    @Inject
    lateinit var ambienceController: com.playfieldportal.core.ui.sound.AmbienceController

    // Voices the settings layer's cursor. Settings navigation lives in composition rather than in
    // a ViewModel (one scaffold, ~40 screens), so the player reaches it as an ambient sink
    // provided here — see LocalMenuSounds for why that layer is the exception.
    @Inject
    lateinit var menuSoundPlayer: com.playfieldportal.core.ui.sound.MenuSoundPlayer

    // Animated Images (Settings ▸ Artwork), provided to the whole UI below.
    @Inject
    lateinit var iconDisplayPreferences: com.playfieldportal.core.data.repository.IconDisplayPreferences

    // Display ▸ Screen Orientation (issue #21): Landscape locks the window; Follow Device lets it
    // turn portrait, where a rotate prompt covers the UI.
    @Inject
    lateinit var screenOrientationPreferences: com.playfieldportal.core.data.repository.ScreenOrientationPreferences

    // Same activity-scoped instance the shell's hiltViewModel() resolves — used to report when
    // the notification-permission dialog is out of the way so the boot sequence can start.
    private val xmbViewModel: XMBViewModel by viewModels()

    // True once the launcher has actually been stopped, so onResume can tell "back from a game"
    // apart from the cold start's own first onResume.
    private var wasStopped = false

    // The splash holds until the saved orientation is applied, so Follow Device never flashes a
    // landscape frame when PFP starts in portrait.
    private var orientationResolved = false

    // The saved mode, from the same collector that releases the splash: the composition reads this
    // rather than collecting again, so the first frame already knows whether the rotate prompt is up.
    private var orientationMode by mutableStateOf(ScreenOrientationMode.LANDSCAPE)

    // Mirrors the composition's rotate-prompt state for key dispatch, which runs outside it.
    private var rotatePromptShowing = false

    // Runtime-registered so it actually fires on Android 8+ (manifest receivers are blocked for
    // this implicit broadcast). Lives for the activity's lifetime.
    private val installShortcutReceiver = InstallShortcutReceiver()

    // Also runtime-registered: ACTION_MEDIA_MOUNTED is an implicit broadcast, so a manifest entry
    // would never fire on Android 8+. Lives for the activity's lifetime.
    private val mediaMountReceiver = MediaMountReceiver()

    // Covers the USB-cable case the mount receiver can't: an MTP transfer never unmounts storage,
    // so unplugging fires no MEDIA_MOUNTED. USB_STATE's disconnect edge is the actual unplug signal.
    private val usbDisconnectReceiver = UsbDisconnectReceiver()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !orientationResolved }
        super.onCreate(savedInstanceState)

        // The manifest's sensorLandscape is the cold-start default; the saved mode replaces it,
        // and a change made in Settings applies live.
        lifecycleScope.launch {
            screenOrientationPreferences.modeFlow.collect { mode ->
                requestedOrientation = requestedOrientationFor(mode)
                orientationMode = mode
                orientationResolved = true
            }
        }

        enableEdgeToEdge()
        hideSystemBars()
        // No runtime prompt any more: PFP's notifications are launcher-only, and the one shade item
        // left (music playback) is a media-session notification, which needs no POST_NOTIFICATIONS.
        // The boot sequence still waits on this signal, so it is reported settled straight away.
        xmbViewModel.onStartupPermissionsSettled()
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
        ContextCompat.registerReceiver(
            this,
            usbDisconnectReceiver,
            IntentFilter(UsbDisconnectReceiver.ACTION_USB_STATE),
            // NOT_EXPORTED: USB_STATE is a protected system broadcast, so only the OS can send it —
            // no need to accept it from other apps, and this is the flag Android recommends for a
            // receiver registered purely for system broadcasts.
            ContextCompat.RECEIVER_NOT_EXPORTED,
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

        // Orphan sweep for the user's ui-media directory (removed slots, stale staging files).
        // Off the main thread; cheap (one directory listing) when there is nothing to remove.
        lifecycleScope.launch {
            runCatching { uiMediaStore.pruneOrphans() }
                .onFailure { Timber.w(it, "Startup UI-media prune failed") }
        }

        setContent {
            PFPTheme {
                // Menu sounds are ambient for the same reason controller prompts are: the layers
                // that need them are composables, not ViewModels. Remembered so the static local
                // is written once — a fresh lambda per recomposition would invalidate the subtree.
                val menuSounds = remember { MenuSoundSink { menuSoundPlayer.play(it) } }
                // Animated Images: the setting, and "may anything animate at all" — false while PFP
                // is behind a game (it is the HOME app, so its UI stays composed there), which
                // holds every animated image still. Also feeds the shared gate that images drawn
                // without ArtworkImage obey.
                val imageMotion by iconDisplayPreferences.imageMotionFlow
                    .collectAsState(initial = com.playfieldportal.core.domain.model.ImageMotion.DEFAULT)
                val lifecycleState by lifecycle.currentStateFlow.collectAsState()
                // RESUMED, not STARTED: a dialog-style app over the launcher leaves it visible but
                // paused, and nothing should animate behind it either.
                val rotatePrompt = showRotatePrompt(orientationMode, LocalConfiguration.current.orientation)
                // Only the one flag, so the root does not recompose on every XMB state change.
                val touchFamily by remember {
                    xmbViewModel.uiState.map { it.resolvedShowTouchButton }.distinctUntilChanged()
                }.collectAsState(initial = false)
                // Covered by the rotate prompt counts as not visible: nothing animates under it.
                val appVisible = lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                    !rotatePrompt
                SideEffect {
                    rotatePromptShowing = rotatePrompt
                    com.playfieldportal.core.ui.motion.MotionGate.Shared
                        .update(imageMotion, focused = true, allowed = appVisible)
                }
                // Ambience yields to the prompt through its own hold, independent of the
                // lifecycle calls that also drive it.
                if (rotatePrompt) {
                    DisposableEffect(Unit) {
                        val hold = ambienceController.hold("rotate-prompt")
                        onDispose { hold.release() }
                    }
                }
                CompositionLocalProvider(
                    LocalMenuSounds provides menuSounds,
                    com.playfieldportal.core.ui.motion.LocalImageMotion provides imageMotion,
                    com.playfieldportal.core.ui.motion.LocalMotionAllowed provides appVisible,
                ) {
                    // Controller prompts are ambient: every footer resolves its glyphs from the
                    // live bindings supplied here, so none of them can contradict the pad.
                    ProvideControllerPrompts {
                        Box(Modifier.fillMaxSize()) {
                            // AppXmbHost is defined per build variant: the debug source set wraps the shell so
                            // long-pressing Settings opens DebugMenuScreen; the release source set calls
                            // XMBShellContainer directly, keeping debug code out of the APK.
                            AppXmbHost()
                            // Drawn over the UI rather than instead of it, so everything underneath
                            // keeps its state and rotating back lands exactly where it was.
                            if (rotatePrompt) {
                                RotateToLandscapeScreen(
                                    onSwitchLauncher = ::openLauncherChooser,
                                    showControllerGlyph = !touchFamily,
                                    onTouchInput = xmbViewModel::markTouchInput,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // B1: PFP is foreground again — classify any pending launch hand-off (success if the
        // emulator held the foreground for a real session, never-foregrounded otherwise).
        launchDispatcher.onHostResumed()
        // Ambience only ever sounds while the launcher is on screen. It still waits on the boot
        // sequence after this (XMBViewModel pushes that gate), so a resume that replays the boot
        // gets the chime first and the music after, exactly like a cold start.
        ambienceController.onHostResumed()
        // Only a RETURN counts for "Show Boot Sequence on Resume" — the very first onResume after
        // onCreate is the cold start, which already plays its own boot.
        if (wasStopped) {
            wasStopped = false
            xmbViewModel.onHostResumed()
        }
        // Foreground again = out of any game, so drop the per-game Discord presence back to idle
        // (full build only; no-op in lite). Cheap unless a game was actually being shared.
        discordBootstrap.onResume()
        // Same "back from a game" moment is the weak rescan signal: it catches ROMs downloaded or
        // deleted while PFP was backgrounded. The coordinator throttles this internally (5 min), so
        // calling it on every resume costs nothing when it fires in quick succession.
        lifecycleScope.launch {
            runCatching {
                libraryRescanCoordinator.onResume()
                mediaRescanCoordinator.onResume()
            }.onFailure { Timber.e(it, "Resume-triggered library rescan failed") }
        }
    }

    override fun onStart() {
        super.onStart()
        // UI-only clocks (idle hints, music position) run only while the launcher is on screen.
        xmbViewModel.setHostVisible(true)
    }

    override fun onPause() {
        // Visible but not in front (a dialog-style app on top): ambience holds its place.
        ambienceController.onHostPaused()
        super.onPause()
    }

    override fun onStop() {
        // B1: another activity covered the launcher — the dispatched emulator came to front.
        launchDispatcher.onHostStopped()
        // Releases the ambience player outright rather than pausing it: a game is about to want
        // the audio hardware, and a paused ExoPlayer still holds a codec.
        ambienceController.onHostStopped()
        xmbViewModel.setHostVisible(false)
        wasStopped = true
        super.onStop()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(installShortcutReceiver) }
        runCatching { unregisterReceiver(mediaMountReceiver) }
        runCatching { unregisterReceiver(usbDisconnectReceiver) }
        super.onDestroy()
    }

    // The rotate prompt's button: the system chooser over every home app except PFP, so a phone
    // held upright can leave for the everyday launcher without a controller.
    private fun openLauncherChooser() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val chooser = Intent.createChooser(home, "Switch launcher")
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, MainActivity::class.java)))
        runCatching { startActivity(chooser) }
            .onFailure { Timber.w(it, "Could not open the launcher chooser") }
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
        // The rotate prompt is a hard input boundary: Confirm presses its button, every other
        // bound button is swallowed, unbound system keys (volume) reach the system and any other
        // key is dropped. A bound button still hands the prompt's glyphs to the controller.
        if (rotatePromptShowing) {
            val key = rotatePromptKey(gamepadInputHandler.currentMappings.actionFor(event.keyCode), event.isSystem)
            if (key.isControllerInput && event.action == KeyEvent.ACTION_DOWN) xmbViewModel.markControllerInput()
            when (key) {
                RotatePromptKey.PASS -> return super.dispatchKeyEvent(event)
                RotatePromptKey.SWALLOW, RotatePromptKey.IGNORE -> return true
                RotatePromptKey.SWITCH_LAUNCHER -> {
                    // On release, so the key-up does not land in the launcher that opens.
                    if (event.action == KeyEvent.ACTION_UP) openLauncherChooser()
                    return true
                }
            }
        }
        // Let the gamepad handler process it first; fall back to normal dispatch
        if (gamepadInputHandler.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // Sticks and HATs drive nothing while the rotate prompt covers the UI.
        if (rotatePromptShowing && (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            return true
        }
        if (gamepadInputHandler.onMotionEvent(event)) return true
        return super.onGenericMotionEvent(event)
    }
}
