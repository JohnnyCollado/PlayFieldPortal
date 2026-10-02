package com.playfieldportal.feature.xmb.video

import com.playfieldportal.core.ui.components.PspMenuRow

internal val VIDEO_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
internal val VIDEO_SCREEN_MODE_LABELS = listOf("Fit", "Zoom", "Fill")

/** The sub-list a root row of the Video Player's options panel opens. */
internal enum class VideoOptionGroup { SPEED, SUBTITLES, AUDIO, SCREEN_MODE }

/** One pickable subtitle or audio entry; [selected] is the one playing now. */
internal data class VideoTrackChoice(val label: String, val selected: Boolean)

/** A supported track as the player reports it, before it becomes a [VideoTrackChoice]. */
internal data class VideoTrackInfo(val language: String?, val label: String?, val selected: Boolean)

/** Everything the options panel shows, with the Media3 reading already done. */
internal data class VideoOptionsState(
    val speed: Float,
    val screenModeLabel: String,
    val subtitleChoices: List<VideoTrackChoice>,
    val audioChoices: List<VideoTrackChoice>,
)

/** "1.5×", and "1×" rather than "1.0×". */
internal fun videoSpeedLabel(speed: Float): String {
    val text = speed.toString().removeSuffix(".0")
    return "$text×"
}

/**
 * The picker entries for one track type: "Off" first when [allowOff], then one per supported track
 * in the order the player lists them — the same order the caller uses to apply a pick by index.
 * With no supported tracks there is nothing to choose, so the list is empty (no lone "Off").
 */
internal fun videoTrackChoices(
    tracks: List<VideoTrackInfo>,
    allowOff: Boolean,
    trackTypeDisabled: Boolean,
): List<VideoTrackChoice> {
    if (tracks.isEmpty()) return emptyList()
    return buildList {
        if (allowOff) add(VideoTrackChoice("Off", selected = trackTypeDisabled || tracks.none { it.selected }))
        tracks.forEach { t ->
            add(
                VideoTrackChoice(
                    label = t.language?.uppercase() ?: t.label ?: "Track",
                    selected = t.selected && !trackTypeDisabled,
                ),
            )
        }
    }
}

/**
 * The rows of the Video Player's options panel: the four options with their current values when
 * [group] is null, otherwise that option's list with the active entry checked.
 */
internal fun videoPlayerOptionRows(state: VideoOptionsState, group: VideoOptionGroup?): List<PspMenuRow> =
    when (group) {
        null -> listOf(
            PspMenuRow("Playback Speed", value = videoSpeedLabel(state.speed), opensMenu = true),
            PspMenuRow("Subtitles", value = state.subtitleChoices.currentLabel("None"), opensMenu = true),
            PspMenuRow("Audio Track", value = state.audioChoices.currentLabel("Default"), opensMenu = true),
            PspMenuRow("Screen Mode", value = state.screenModeLabel, opensMenu = true),
        )
        VideoOptionGroup.SPEED -> VIDEO_SPEEDS.map { PspMenuRow(videoSpeedLabel(it), checked = it == state.speed) }
        VideoOptionGroup.SUBTITLES -> state.subtitleChoices.map { PspMenuRow(it.label, checked = it.selected) }
        VideoOptionGroup.AUDIO -> state.audioChoices.map { PspMenuRow(it.label, checked = it.selected) }
        VideoOptionGroup.SCREEN_MODE -> VIDEO_SCREEN_MODE_LABELS.map {
            PspMenuRow(it, checked = it == state.screenModeLabel)
        }
    }

private fun List<VideoTrackChoice>.currentLabel(whenEmpty: String): String =
    if (isEmpty()) whenEmpty else firstOrNull { it.selected }?.label ?: whenEmpty
