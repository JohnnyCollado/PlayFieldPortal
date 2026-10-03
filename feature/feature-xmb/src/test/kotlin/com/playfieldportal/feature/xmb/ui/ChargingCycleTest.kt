package com.playfieldportal.feature.xmb.ui

import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The charging animation steps the battery through its fill tiers, from the real level up to full.
 * Pinned here because the frames are what the user reads as "how charged am I": the cycle must
 * always START at the current tier, never below it.
 */
class ChargingCycleTest {

    @Test
    fun `an empty battery cycles through every tier`() {
        assertEquals(
            listOf("status_battery_low", "status_battery_medium", "status_battery_high", "status_battery_full"),
            XmbStatusIcons.chargingCycleSlotKeys(10),
        )
    }

    @Test
    fun `the cycle starts at the current tier, not below it`() {
        assertEquals(
            listOf("status_battery_medium", "status_battery_high", "status_battery_full"),
            XmbStatusIcons.chargingCycleSlotKeys(40),
        )
        assertEquals(
            listOf("status_battery_high", "status_battery_full"),
            XmbStatusIcons.chargingCycleSlotKeys(60),
        )
    }

    @Test
    fun `a nearly full battery still animates between high and full`() {
        assertEquals(
            listOf("status_battery_high", "status_battery_full"),
            XmbStatusIcons.chargingCycleSlotKeys(90),
        )
    }

    @Test
    fun `a full battery holds on the full tier`() {
        assertEquals(listOf("status_battery_full"), XmbStatusIcons.chargingCycleSlotKeys(100))
    }

    // ── Power state: what the strip reads out of ACTION_BATTERY_CHANGED ────────────────────

    @Test
    fun `reported charging is charging`() {
        assertEquals(
            BatteryPowerState.CHARGING,
            batteryPowerStateOf(BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_PLUGGED_AC),
        )
    }

    @Test
    fun `full on the charger still counts as charging`() {
        assertEquals(
            BatteryPowerState.CHARGING,
            batteryPowerStateOf(BatteryManager.BATTERY_STATUS_FULL, BatteryManager.BATTERY_PLUGGED_USB),
        )
    }

    @Test
    fun `plugged in but discharging is plugged, not charging`() {
        // The AYN Odin 3 on a 15 W charger: AC powered, yet status DISCHARGING and the level falling.
        // It earns the bolt but not the fill animation.
        assertEquals(
            BatteryPowerState.PLUGGED,
            batteryPowerStateOf(BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_PLUGGED_AC),
        )
    }

    @Test
    fun `plugged in but not charging is plugged`() {
        // Charge limits and bypass charging report NOT_CHARGING while on power.
        assertEquals(
            BatteryPowerState.PLUGGED,
            batteryPowerStateOf(BatteryManager.BATTERY_STATUS_NOT_CHARGING, BatteryManager.BATTERY_PLUGGED_WIRELESS),
        )
    }

    @Test
    fun `off the charger is unplugged`() {
        assertEquals(
            BatteryPowerState.UNPLUGGED,
            batteryPowerStateOf(BatteryManager.BATTERY_STATUS_DISCHARGING, 0),
        )
    }

    @Test
    fun `every frame resolves to built-in art`() {
        for (level in 0..100) {
            for (key in XmbStatusIcons.chargingCycleSlotKeys(level)) {
                assertNotNull("$key at $level% has no drawable", XmbStatusIcons.forSlotKey(key))
            }
        }
    }
}
