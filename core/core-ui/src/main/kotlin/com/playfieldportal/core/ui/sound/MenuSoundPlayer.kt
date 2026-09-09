package com.playfieldportal.core.ui.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.core.ui.media.bundledDefaultRes
import com.playfieldportal.core.ui.R
import com.playfieldportal.core.ui.media.UiMediaPaths
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber

/** UI sound effects for XMB menu interactions (see `assets/SFX/active/README.md` for the set). */
enum class MenuSound {
    SCROLL,         // item navigate up/down
    SYSTEM_BROWSE,  // category / filter change
    SELECT,         // open a folder / detail / picker
    /**
     * A committed action, as opposed to [SELECT]'s navigation: Confirm/Yes/OK in a modal, and
     * Save/Add/Apply/Confirm in a picker (app picker, custom icon picker, game picker in game
     * categories). [SELECT] descends into something you can back out of; this one is the point
     * of no return, so it gets its own longer, lower cue.
     */
    CONFIRM,
    BACK,           // back / close
    LAUNCH,         // launch a game or app
    /**
     * A background task finished, or something worth surfacing arrived — a library rescan, a
     * backup, an achievement sync. Fired on completion, never on progress.
     *
     * PARKED: [play] currently DROPS this event. It fired at the end of every full-library
     * rescan — which the rescan bus runs on app resume, media mount and USB unplug — plus
     * backup/restore completion, so it landed at seemingly random moments. The bundled
     * `sfx_notification` sample, the SOUND_NOTIFICATION slot and the Sound screen row all stay,
     * and the event's call sites are left in place; the gate inside [play] is the single switch
     * to lift when the event gets real, deliberate triggers. Preview (ignoreMute) still
     * auditions it so a saved assignment stays testable.
     *
     * `sfx_notification` is the slot that used to be mis-registered as the Favorite sound, and
     * naming it correctly here is what stops it being customized under the wrong label.
     */
    NOTIFICATION,
    /**
     * A refused action — an invalid pick, a launch that cannot proceed. `sfx_error` is the
     * file that used to be registered as the Back sound.
     */
    ERROR,
    ;

    /** The user-customizable slot this event resolves through (Interface ▸ Sound).
     *
     * SCROLL, SELECT and SYSTEM_BROWSE all land on [UiMediaSlot.SOUND_SCROLL]: the merged
     * Navigation row is one sample covering three events — a default, not a law (75+ call sites
     * still distinguish the events, and `MenuSound` itself is unchanged).
     */
    val slot: UiMediaSlot
        get() = when (this) {
            SCROLL -> UiMediaSlot.SOUND_SCROLL
            SYSTEM_BROWSE -> UiMediaSlot.SOUND_SCROLL
            SELECT -> UiMediaSlot.SOUND_SCROLL
            CONFIRM -> UiMediaSlot.SOUND_CONFIRM
            BACK -> UiMediaSlot.SOUND_BACK
            LAUNCH -> UiMediaSlot.SOUND_LAUNCH
            NOTIFICATION -> UiMediaSlot.SOUND_NOTIFICATION
            ERROR -> UiMediaSlot.SOUND_ERROR
        }
}

/**
 * Low-latency player for short menu sounds, backed by [SoundPool]. Samples are loaded once into
 * memory; [play] no-ops until a sample has finished loading and whenever menu sounds are muted.
 *
 * Singleton so the pool and loaded samples live for the app's lifetime — menu sounds fire on nearly
 * every navigation, so re-creating the pool per screen would add latency and churn. Lives in
 * core-ui so both the XMB shell and the app drawer (feature-appbar) can share one instance.
 *
 * **This is the only file that knows a menu sound can come from anywhere but `R.raw`.** All ~90
 * call sites keep calling `play(MenuSound.X)`; the custom/default decision lives here and nowhere
 * else, so a feature screen can never grow a URI branch.
 *
 * Resolution per event: the user's imported sample if it exists AND finished loading, otherwise the
 * bundled default. The bundled samples are loaded in [init] and are NEVER unloaded — they are the
 * fallback, and a fallback that can be evicted is not one.
 */
@Singleton
class MenuSoundPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uiMedia: UiMediaPaths,
) {
    /**
     * When false, [play] is a no-op. Observed here from `sound_menu_enabled` rather than pushed in
     * by a ViewModel: the app drawer and game detail play through this same singleton, and before
     * this the mute flag only survived while XMBViewModel happened to be alive.
     */
    @Volatile
    var enabled: Boolean = true
        private set

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                // USAGE_GAME follows media volume and stays audible — unlike
                // ASSISTANCE_SONIFICATION, which some handhelds gate behind system-sound settings.
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    // Slot -> SoundPool sample id for the BUNDLED defaults, one load per DISTINCT slot —
    // Navigation's three events share the sfx_cursor sample by construction. Written once in init.
    private val defaultIds = HashMap<UiMediaSlot, Int>()

    // Slot -> sample id for the USER's imported sounds. Replaced wholesale on reload, so
    // play() always reads a consistent snapshot rather than a half-rebuilt map. Keyed by slot
    // for the same reason: three events on one slot must not load one file three times.
    @Volatile
    private var customIds: Map<UiMediaSlot, Int> = emptyMap()

    // Sample ids SoundPool has finished decoding. Written from SoundPool's callback thread and
    // read from whichever thread calls play(), hence the concurrent set.
    private val loaded: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    // The player outlives every screen, so it owns its scope rather than borrowing a ViewModel's.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loaded.add(sampleId)
            else Timber.w("Menu sound sample $sampleId failed to load (status=$status)")
        }
        // One bundled load per DISTINCT event slot. The slot→res mapping lives in
        // UiMediaDefaults.bundledDefaultRes() so the boot pipeline cannot drift from it.
        for (slot in MenuSound.entries.map { it.slot }.distinct()) {
            val res = slot.bundledDefaultRes() ?: continue
            defaultIds[slot] = pool.load(context, res, 1)
        }

        // Reload custom samples whenever the store's stamp bumps — an import, a clear, or a
        // restored backup. Without this a replaced sound keeps playing the old sample forever.
        uiMedia.stamp
            .distinctUntilChanged()
            .onEach { reload() }
            .launchIn(scope)

        uiMedia.menuSoundsEnabled
            .distinctUntilChanged()
            .onEach { enabled = it }
            .launchIn(scope)
    }

    /**
     * Plays [sound], resolving the user's sample over the bundled one — both looked up through
     * [MenuSound.slot], so the merged Navigation row is one customization for three events.
     *
     * [ignoreMute] exists for the Sound screen's Preview button only: a user auditioning a sound
     * they just picked must hear it even with Menu Sounds off. It is a defaulted parameter so no
     * ordinary call site changes.
     *
     * Rapid navigation needs no debouncing here — SoundPool's own oldest-stream eviction at
     * `maxStreams = 4` already gives "interrupt rather than queue".
     */
    fun play(sound: MenuSound, ignoreMute: Boolean = false) {
        if (!enabled && !ignoreMute) return
        // PARKED (Notification): the event is cut from ordinary playback — it fired on every
        // full-library rescan (app resume, media mount, USB unplug) and backup/restore, which
        // read as random chimes. Everything around it stays: the bundled sample loads, the slot
        // is assignable and previewable, and the call sites keep firing this event. This is the
        // one line to remove when the event gets its real triggers. See MenuSound.NOTIFICATION.
        if (sound == MenuSound.NOTIFICATION && !ignoreMute) return
        val id = customIds[sound.slot]?.takeIf { it in loaded } ?: defaultIds[sound.slot] ?: return
        // Skip if the sample hasn't finished decoding yet — better silent than a click/glitch.
        // A custom sample that never loaded has already fallen through to the default above.
        if (id !in loaded) return
        pool.play(id, 1f, 1f, 1, 0, 1f)
    }

    /**
     * Rebuilds the custom sample set from [UiMediaPaths]. Old custom ids are unloaded (the bundled
     * defaults never are). Safe to call repeatedly; runs off the main thread via [scope].
     */
    private fun reload() {
        val previous = customIds
        val next = HashMap<UiMediaSlot, Int>()
        for (slot in MenuSound.entries.map { it.slot }.distinct()) {
            val path = runCatching { uiMedia.pathFor(slot) }.getOrNull() ?: continue
            val id = runCatching { pool.load(path, 1) }.getOrNull() ?: continue
            if (id == 0) continue   // SoundPool reports 0 for a source it could not open
            next[slot] = id
        }
        customIds = next
        for (id in previous.values) {
            loaded.remove(id)
            runCatching { pool.unload(id) }
        }
    }

    /** Immediate reload, for the settings screen right after an import commits. */
    fun refreshCustomSamples() {
        scope.launch { reload() }
    }
}
