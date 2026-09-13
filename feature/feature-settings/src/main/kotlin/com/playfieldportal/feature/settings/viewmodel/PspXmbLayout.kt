package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.themekit.XmbFormFactor
import com.playfieldportal.themekit.XmbLayoutAdjust
import com.playfieldportal.themekit.XmbLayoutAdjustCodec
import com.playfieldportal.themekit.XmbLayoutPreset

/**
 * The PSP-proportioned XMB layout for the device's current window, and whether it is the layout
 * saved for that window's screen-size bucket. Shared by the setup wizard's auto-fit checkbox and
 * Display ▸ XMB Layout ▸ Biblically Accurate PSP XMB, so the two always write the same thing.
 */
internal object PspXmbLayout {

    // Must match XMBViewModel.KEY_XMB_LAYOUT_ADJUST: the Adjust XMB Layout editor reads and writes it.
    val KEY = stringPreferencesKey("display_xmb_layout_adjust")

    /** The bucket the Adjust XMB Layout editor keys by, and the preset for the window as it is now. */
    data class Target(val bucketKey: String, val preset: XmbLayoutAdjust)

    fun forWindow(context: Context): Target {
        val config = context.resources.configuration
        return Target(
            bucketKey = XmbFormFactor.forSmallestWidthDp(config.smallestScreenWidthDp).key,
            preset = XmbLayoutPreset.computeForWindowDp(
                widthDp = config.screenWidthDp.toFloat(),
                heightDp = config.screenHeightDp.toFloat(),
                density = context.resources.displayMetrics.density,
            ),
        )
    }

    /**
     * True while the layout saved for [target]'s bucket is the preset. Derived, never stored: any
     * change saved from the Adjust XMB Layout editor, a reset to default included, stops it matching.
     */
    fun isApplied(prefs: Preferences, target: Target): Boolean =
        XmbLayoutPreset.matches(XmbLayoutAdjustCodec.decode(prefs[KEY])[target.bucketKey], target.preset)

    /** Saves the preset for [target]'s bucket, leaving every other bucket's tuning alone. */
    fun write(prefs: MutablePreferences, target: Target) {
        val map = XmbLayoutAdjustCodec.decode(prefs[KEY]).toMutableMap()
        map[target.bucketKey] = target.preset
        prefs[KEY] = XmbLayoutAdjustCodec.encode(map)
    }
}
