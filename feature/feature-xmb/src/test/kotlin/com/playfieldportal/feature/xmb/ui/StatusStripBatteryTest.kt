package com.playfieldportal.feature.xmb.ui

import android.app.Application
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.ui.preview.PfpScreenPreview
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The status strip's battery readout end to end: fake ACTION_BATTERY_CHANGED broadcasts and a fake
 * BatteryManager capacity go in, the strip's text comes out. [BatteryPercentTest] pins the math;
 * this pins the wiring around it — the receiver, the sticky broadcast on start, the seed reading
 * and the hidden number — for the broadcast shapes that left a Retroid Pocket 6 reading 0%.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class StatusStripBatteryTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        // Below SDK 33 ContextCompat.registerReceiver checks the app's own NOT_EXPORTED permission,
        // which only an app manifest declares. Grant it here (as XmbDeviceFormatScreenshotTest does).
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }

    @Test
    fun `a broadcast with no scale reads as a percentage, not 0`() {
        sendBattery(level = 73, scale = null)
        showStrip()
        assertShows("73%")
    }

    @Test
    fun `mismatched units fall back to the capacity reading`() {
        setCapacity(73)
        sendBattery(level = 73, scale = 10_000)
        showStrip()
        assertShows("73%")
    }

    @Test
    fun `a broadcast with no level falls back to the capacity reading`() {
        setCapacity(64)
        sendBattery(level = null, scale = 100)
        showStrip()
        assertShows("64%")
    }

    @Test
    fun `the capacity reading shows before any broadcast arrives`() {
        setCapacity(80)
        showStrip()
        assertShows("80%")
    }

    @Test
    fun `no usable reading hides the number instead of showing 0`() {
        sendBattery(level = null, scale = null)
        showStrip()
        composeRule.onAllNodesWithText("%", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a later broadcast updates the readout`() {
        sendBattery(level = 50, scale = 100)
        showStrip()
        assertShows("50%")

        sendBattery(level = 49, scale = 100)
        // Deliver the broadcast, then give the hand-driven clock a frame to recompose.
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.mainClock.advanceTimeBy(100)
        assertShows("49%")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun showStrip() {
        // The strip's clock and charging cycle never go idle on their own; drive the clock by hand.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { PfpScreenPreview { XmbPspStatusStrip() } }
        composeRule.mainClock.advanceTimeBy(100)
    }

    private fun assertShows(text: String) {
        composeRule.onNodeWithText(text).assertExists()
        composeRule.onAllNodesWithText("0%").assertCountEquals(0)
    }

    private fun setCapacity(percent: Int) {
        val manager = app.getSystemService(BatteryManager::class.java)
        shadowOf(manager).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, percent)
    }

    /** A sticky battery broadcast, on battery power; a null extra is left out entirely. */
    @Suppress("DEPRECATION") // Only the system sends this for real; sticky is how it arrives.
    private fun sendBattery(level: Int?, scale: Int?) {
        val intent = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            level?.let { putExtra(BatteryManager.EXTRA_LEVEL, it) }
            scale?.let { putExtra(BatteryManager.EXTRA_SCALE, it) }
            putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING)
            putExtra(BatteryManager.EXTRA_PLUGGED, 0)
        }
        app.sendStickyBroadcast(intent)
    }
}
