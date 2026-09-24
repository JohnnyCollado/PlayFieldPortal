package com.playfieldportal.core.ui.sound

import com.playfieldportal.core.domain.model.AudioChannel
import kotlinx.coroutines.flow.Flow

/**
 * Read-only view of the user's volume levels for components living in core-ui.
 *
 * Same seam as [com.playfieldportal.core.ui.media.UiMediaPaths]: the concrete store lives in
 * core-data, which depends on core-ui, so core-ui cannot see it without a Gradle cycle. The
 * interface is here and the Hilt binding is over there.
 *
 * **Everything is a flow, nothing is a snapshot.** A player that reads its level once at start
 * cannot respond to the slider that is being dragged while it plays, which makes the settings
 * screen feel broken. One-shot callers may still collapse a flow into a cached value — see
 * [MenuSoundPlayer] — but that is the caller's decision, not this interface's.
 */
interface AudioLevels {

    /**
     * What [channel] should actually be played at: master × the channel's own level, tapered.
     * This is the only value a player should ever multiply by — the two percentages and the
     * taper are resolved here so eight call sites cannot each get it slightly different.
     */
    fun gainFor(channel: AudioChannel): Flow<Float>

    /** The master level as the user set it, 0..1. For the settings screen's slider only. */
    val masterPercent: Flow<Float>

    /** [channel]'s own level as the user set it, 0..1. For the settings screen's sliders only. */
    fun percentFor(channel: AudioChannel): Flow<Float>

    companion object {
        /**
         * Perceived loudness is roughly logarithmic, so a linear slider mapped straight to
         * amplitude sounds wrong — half travel reads as about three-quarters as loud, and the
         * bottom of every slider does almost nothing. A square-law taper is the cheapest fix that
         * behaves: monotonic, exactly 0 at 0 and 1 at 1, no special cases.
         *
         * The percentages are what the user sees and what is stored; the square is an
         * implementation detail that lives HERE and nowhere else, so the resolution can never
         * drift between channels.
         */
        fun taper(masterPercent: Float, channelPercent: Float): Float {
            val linear = (masterPercent.coerceIn(0f, 1f) * channelPercent.coerceIn(0f, 1f))
            return linear * linear
        }
    }
}
