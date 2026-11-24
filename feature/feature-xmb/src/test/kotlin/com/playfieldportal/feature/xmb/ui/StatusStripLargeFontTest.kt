package com.playfieldportal.feature.xmb.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.ui.preview.PfpScreenPreview
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The status strip's pills grow with the system font size rather than cutting their text: a large
 * font (here 200%, the largest Android offers) must leave the sort label whole inside its pill.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h360dp-land")
class StatusStripLargeFontTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the sort pill's label is not cut at a large font size`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        composeRule.setContent {
            PfpScreenPreview {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale = 2.0f),
                    LocalSystemStatusOverride provides SystemStatus(wifiLevel = 4, controllerConnected = true),
                ) {
                    XmbPspStatusStrip(sortLabel = "Recently Played", showTouchControls = true)
                }
            }
        }
        val label = composeRule.onNodeWithText("Recently Played", substring = true).fetchSemanticsNode()
        // The text's own laid-out height against the box it was given: a pill too short for the text
        // squeezes the box and cuts the lines inside it.
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        label.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val needed = kotlin.math.ceil(layouts.first().multiParagraph.height).toInt()
        val given = label.size.height
        assertTrue("label cut: needs $needed px, given $given px", given >= needed)
    }
}
