package com.playfieldportal.core.domain.model

import kotlin.math.abs

/**
 * Colours in words. The pickers show these beside every swatch and slider so a colour can be
 * chosen without judging its hue by eye — the swatch confirms the words, not the other way round.
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

    private class Band(val start: Float, val name: String, val comparative: String)

    // Around the circle from orange; red is last and runs through 360 to 375 (15 on the next lap).
    private val BANDS = listOf(
        Band(15f, "orange", "more orange"),
        Band(45f, "yellow", "yellower"),
        Band(70f, "green", "greener"),
        Band(160f, "teal", "more teal"),
        Band(200f, "blue", "bluer"),
        Band(250f, "purple", "more purple"),
        Band(290f, "pink", "pinker"),
        Band(335f, "red", "redder"),
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
    fun hueName(hue: Float): String = BANDS[bandIndex(hue)].name

    /** [hue] as a phrase, with the neighbour it leans toward near a band's edge: "Purple, leaning blue". */
    fun hueDescription(hue: Float): String = sentence(huePhrase(hue))

    private fun huePhrase(hue: Float): String {
        val h = lapped(hue)
        val index = bandIndex(hue)
        val start = BANDS[index].start
        val end = BANDS.getOrNull(index + 1)?.start ?: CIRCLE_END
        val margin = (end - start) * LEAN_FRACTION
        val lean = when {
            h < start + margin -> neighbour(index, -1)
            h > end - margin -> neighbour(index, +1)
            else -> null
        }
        return BANDS[index].name + (lean?.let { ", leaning ${it.name}" } ?: "")
    }

    fun saturationWord(saturation: Float): String = when {
        saturation < GREY_SATURATION -> "Grey"
        saturation < MUTED_SATURATION -> "Muted"
        saturation < VIVID_SATURATION -> "Vivid"
        else -> "Intense"
    }

    fun brightnessWord(brightness: Float): String = when {
        brightness < VERY_DARK_BRIGHTNESS -> "Very dark"
        brightness < DARK_BRIGHTNESS -> "Dark"
        brightness < MEDIUM_BRIGHTNESS -> "Medium"
        else -> "Bright"
    }

    /** A whole colour in one phrase: "Dark muted green", "Purple, leaning blue", "Bright grey". */
    fun describe(argb: Long): String {
        val (hue, saturation, brightness) = ColorMath.argbToHsv(argb)
        if (saturation < GREY_SATURATION) return "${brightnessWord(brightness)} grey"
        val words = buildList {
            if (brightness < DARK_BRIGHTNESS) add(brightnessWord(brightness).lowercase())
            if (saturation < MUTED_SATURATION) add("muted")
            add(huePhrase(hue))
        }
        return sentence(words.joinToString(" "))
    }

    /**
     * The label of a tint step away from [baseArgb]: the neighbouring hue it moves toward ("Bluer",
     * "Much pinker"), or "Cooler" / "Warmer" for a grey. Negative steps run down the hue circle.
     */
    fun tintLabel(baseArgb: Long, tint: Int): String {
        if (tint == 0) return STANDARD
        val (hue, saturation, _) = ColorMath.argbToHsv(baseArgb)
        val direction = when {
            saturation < GREY_SATURATION -> if (tint < 0) "cooler" else "warmer"
            else -> neighbour(bandIndex(hue), if (tint < 0) -1 else +1).comparative
        }
        return sentence(if (abs(tint) > 1) "much $direction" else direction)
    }

    /** The label of a depth step: negative is lighter, positive deeper. */
    fun depthLabel(depth: Int): String = when {
        depth == 0 -> STANDARD
        depth < -1 -> "Much lighter"
        depth < 0 -> "Lighter"
        depth > 1 -> "Much deeper"
        else -> "Deeper"
    }

    private const val STANDARD = "Standard"

    private fun sentence(text: String): String = text.replaceFirstChar { it.uppercase() }
}
