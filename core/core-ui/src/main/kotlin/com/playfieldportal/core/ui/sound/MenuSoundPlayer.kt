package com.playfieldportal.core.ui.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.playfieldportal.core.ui.R
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** UI sound effects for XMB menu interactions (sourced from the XMB/ES-DE sound set). */
enum class MenuSound {
    SCROLL,         // item navigate up/down
    SYSTEM_BROWSE,  // category / filter change
    SELECT,         // open a folder / detail / picker
    BACK,           // back / close
    LAUNCH,         // launch a game or app
    FAVORITE,       // toggle favorite
}

/**
 * Low-latency player for short menu sounds, backed by [SoundPool]. Samples are loaded once into
 * memory; [play] no-ops until a sample has finished loading and whenever [enabled] is false.
 *
 * Singleton so the pool and loaded samples live for the app's lifetime — menu sounds fire on nearly
 * every navigation, so re-creating the pool per screen would add latency and churn. Lives in
 * core-ui so both the XMB shell and the app drawer (feature-appbar) can share one instance.
 */
@Singleton
class MenuSoundPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** When false, [play] is a no-op (driven by the user's "menu sounds" setting). */
    @Volatile
    var enabled: Boolean = true

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

    // MenuSound -> SoundPool sample id. A sample is only playable once it appears here.
    private val sampleIds = HashMap<MenuSound, Int>()
    private val loaded = HashSet<Int>()

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loaded.add(sampleId)
            else Timber.w("Menu sound sample $sampleId failed to load (status=$status)")
        }
        load(MenuSound.SCROLL, R.raw.sfx_scroll)
        load(MenuSound.SYSTEM_BROWSE, R.raw.sfx_systembrowse)
        load(MenuSound.SELECT, R.raw.sfx_select)
        load(MenuSound.BACK, R.raw.sfx_back)
        load(MenuSound.LAUNCH, R.raw.sfx_launch)
        load(MenuSound.FAVORITE, R.raw.sfx_favorite)
    }

    private fun load(sound: MenuSound, resId: Int) {
        sampleIds[sound] = pool.load(context, resId, 1)
    }

    fun play(sound: MenuSound) {
        if (!enabled) return
        val id = sampleIds[sound] ?: return
        // Skip if the sample hasn't finished decoding yet — better silent than a click/glitch.
        if (id !in loaded) return
        pool.play(id, 1f, 1f, 1, 0, 1f)
    }
}
