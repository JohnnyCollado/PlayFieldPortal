package com.playfieldportal.core.domain.model

import com.playfieldportal.core.domain.R
import com.playfieldportal.core.domain.text.UiText
import kotlin.math.abs

/**
 * Colours in words. The pickers show these beside every swatch and slider so a colour can be
 * chosen without judging its hue by eye — the swatch confirms the words, not the other way round.
 *
 * Every phrase is a [UiText] built from whole-phrase format strings (`Dark %1$s`, `%1$s, leaning
 * %2$s`), never from English words strung together, so a translation can order the words its own
 * way. Capitalisation is part of the strings: a description that starts the sentence uses the
 * `_cap` colour names, one that follows a modifier uses the lower-case ones.
 */
object ColorNaming {

    /** Below this saturation a colour reads as a grey, and is named and tinted as one. */
    const val GREY_SATURATION = 0.2f

    private const val MUTED_SATURATION = 0.35f
    private const val VIVID_SATURATION = 0.7f
    private const val VERY_DARK_BRIGHTNESS = 0.3f
    private const val DARK_BRIGHTNESS = 0.55f
    private const val MEDIUM_BRIGHTNESS = 0.8f

    /** The share of a band, at each end, that "leans" toward the neighbouring band. */
    private const val LEAN_FRACTION = 0.2f

    /**
     * One band of the hue circle: its lower-case name, its capitalised name, and the stepper labels
     * for moving the colour toward it ("Bluer", "Much bluer").
     */
    private class Band(val start: Float, val name: Int, val capName: Int, val tint: Int, val muchTint: Int)

    // Around the circle from orange; red is last and runs through 360 to 375 (15 on the next lap).
    private val BANDS = listOf(
        Band(15f, R.string.dom_hue_orange, R.string.dom_hue_orange_cap, R.string.dom_tint_orange, R.string.dom_tint_much_orange),
        Band(45f, R.string.dom_hue_yellow, R.string.dom_hue_yellow_cap, R.string.dom_tint_yellow, R.string.dom_tint_much_yellow),
        Band(70f, R.string.dom_hue_green, R.string.dom_hue_green_cap, R.string.dom_tint_green, R.string.dom_tint_much_green),
        Band(160f, R.string.dom_hue_teal, R.string.dom_hue_teal_cap, R.string.dom_tint_teal, R.string.dom_tint_much_teal),
        Band(200f, R.string.dom_hue_blue, R.string.dom_hue_blue_cap, R.string.dom_tint_blue, R.string.dom_tint_much_blue),
        Band(250f, R.string.dom_hue_purple, R.string.dom_hue_purple_cap, R.string.dom_tint_purple, R.string.dom_tint_much_purple),
        Band(290f, R.string.dom_hue_pink, R.string.dom_hue_pink_cap, R.string.dom_tint_pink, R.string.dom_tint_much_pink),
        Band(335f, R.string.dom_hue_red, R.string.dom_hue_red_cap, R.string.dom_tint_red, R.string.dom_tint_much_red),
    )
    private val CIRCLE_END = BANDS.first().start + 360f

    /** [hue] on the lap the bands are laid out on: 15 until 375. */
    private fun lapped(hue: Float): Float {
        val h = ((hue % 360f) + 360f) % 360f
        return if (h < BANDS.first().start) h + 360f else h
    }

    private fun bandIndex(hue: Float): Int {
        val h = lapped(hue)
        return BANDS.indexOfLast { h >= it.start }
    }

    private fun neighbour(index: Int, step: Int): Band = BANDS[(index + step + BANDS.size) % BANDS.size]

    /** The plain, lower-case name of [hue]'s band: "purple". */
    fun hueName(hue: Float): UiText = UiText.res(BANDS[bandIndex(hue)].name)

    /** [hue] as a phrase, with the neighbour it leans toward near a band's edge: "Purple, leaning blue". */
    fun hueDescription(hue: Float): UiText = huePhrase(hue, startsSentence = true)

    // [startsSentence]: the phrase stands alone (or opens a longer one), so its first word is capitalised.
    private fun huePhrase(hue: Float, startsSentence: Boolean): UiText {
        val h = lapped(hue)
        val index = bandIndex(hue)
        val band = BANDS[index]
        val start = band.start
        val end = BANDS.getOrNull(index + 1)?.start ?: CIRCLE_END
        val margin = (end - start) * LEAN_FRACTION
        val lean = when {
            h < start + margin -> neighbour(index, -1)
            h > end - margin -> neighbour(index, +1)
            else -> null
        }
        val name = UiText.res(if (startsSentence) band.capName else band.name)
        return if (lean == null) name
        else UiText.res(if (startsSentence) R.string.dom_hue_lean_cap else R.string.dom_hue_lean, name, UiText.res(lean.name))
    }

    fun saturationWord(saturation: Float): UiText = UiText.res(
        when {
            saturation < GREY_SATURATION -> R.string.dom_saturation_grey
            saturation < MUTED_SATURATION -> R.string.dom_saturation_muted
            saturation < VIVID_SATURATION -> R.string.dom_saturation_vivid
            else -> R.string.dom_saturation_intense
        },
    )

    fun brightnessWord(brightness: Float): UiText = UiText.res(
        when {
            brightness < VERY_DARK_BRIGHTNESS -> R.string.dom_brightness_very_dark
            brightness < DARK_BRIGHTNESS -> R.string.dom_brightness_dark
            brightness < MEDIUM_BRIGHTNESS -> R.string.dom_brightness_medium
            else -> R.string.dom_brightness_bright
        },
    )

    /** A whole colour in one phrase: "Dark muted green", "Purple, leaning blue", "Bright grey". */
    fun describe(argb: Long): UiText {
        val (hue, saturation, brightness) = ColorMath.argbToHsv(argb)
        if (saturation < GREY_SATURATION) return UiText.res(R.string.dom_color_grey, brightnessWord(brightness))
        val veryDark = brightness < VERY_DARK_BRIGHTNESS
        val dark = brightness < DARK_BRIGHTNESS
        val muted = saturation < MUTED_SATURATION
        if (!dark && !muted) return huePhrase(hue, startsSentence = true)
        val pattern = when {
            veryDark && muted -> R.string.dom_color_very_dark_muted
            veryDark -> R.string.dom_color_very_dark
            dark && muted -> R.string.dom_color_dark_muted
            dark -> R.string.dom_color_dark
            else -> R.string.dom_color_muted
        }
        return UiText.res(pattern, huePhrase(hue, startsSentence = false))
    }

    /**
     * The label of a tint step away from [baseArgb]: the neighbouring hue it moves toward ("Bluer",
     * "Much pinker"), or "Cooler" / "Warmer" for a grey. Negative steps run down the hue circle.
     */
    fun tintLabel(baseArgb: Long, tint: Int): UiText {
        if (tint == 0) return UiText.res(R.string.dom_tint_standard)
        val (hue, saturation, _) = ColorMath.argbToHsv(baseArgb)
        val much = abs(tint) > 1
        if (saturation < GREY_SATURATION) {
            return UiText.res(
                when {
                    tint < 0 -> if (much) R.string.dom_tint_much_cooler else R.string.dom_tint_cooler
                    else -> if (much) R.string.dom_tint_much_warmer else R.string.dom_tint_warmer
                },
            )
        }
        val toward = neighbour(bandIndex(hue), if (tint < 0) -1 else +1)
        return UiText.res(if (much) toward.muchTint else toward.tint)
    }

    /** The label of a depth step: negative is lighter, positive deeper. */
    fun depthLabel(depth: Int): UiText = UiText.res(
        when {
            depth == 0 -> R.string.dom_tint_standard
            depth < -1 -> R.string.dom_depth_much_lighter
            depth < 0 -> R.string.dom_depth_lighter
            depth > 1 -> R.string.dom_depth_much_deeper
            else -> R.string.dom_depth_deeper
        },
    )
}
