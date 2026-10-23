package com.playfieldportal.feature.xmb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The strip's percentage, from ACTION_BATTERY_CHANGED's EXTRA_LEVEL / EXTRA_SCALE (-1 when an
 * extra is missing) with BatteryManager's BATTERY_PROPERTY_CAPACITY as the backup reading.
 * Pinned because a Retroid Pocket 6 showed 0% with charge left: a bad or missing extra must never
 * read as an empty battery.
 */
class BatteryPercentTest {

    // Capacity as BatteryManager reports it when the property is unsupported (targetSdk >= P).
    private val noCapacity = Int.MIN_VALUE

    @Test
    fun `a normal broadcast reads level over scale`() {
        assertEquals(73, batteryPercentOf(level = 73, scale = 100, capacity = noCapacity))
        assertEquals(50, batteryPercentOf(level = 128, scale = 256, capacity = noCapacity))
    }

    @Test
    fun `the broadcast wins over capacity when it makes sense`() {
        assertEquals(73, batteryPercentOf(level = 73, scale = 100, capacity = 72))
    }

    @Test
    fun `a missing scale is treated as 100`() {
        assertEquals(73, batteryPercentOf(level = 73, scale = -1, capacity = noCapacity))
    }

    @Test
    fun `a zero scale is treated as 100`() {
        assertEquals(73, batteryPercentOf(level = 73, scale = 0, capacity = noCapacity))
    }

    @Test
    fun `a missing level falls back to capacity`() {
        assertEquals(64, batteryPercentOf(level = -1, scale = 100, capacity = 64))
    }

    @Test
    fun `mismatched units that round to zero fall back to capacity`() {
        // A level in percent against a scale in hundredths of a percent: 73 * 100 / 10000 = 0.
        assertEquals(73, batteryPercentOf(level = 73, scale = 10_000, capacity = 73))
    }

    @Test
    fun `an out-of-range reading falls back to capacity`() {
        assertEquals(73, batteryPercentOf(level = 7_300, scale = 100, capacity = 73))
    }

    @Test
    fun `an out-of-range reading with no capacity is unknown`() {
        assertNull(batteryPercentOf(level = 7_300, scale = 100, capacity = noCapacity))
    }

    @Test
    fun `nothing valid is unknown, not zero`() {
        assertNull(batteryPercentOf(level = -1, scale = -1, capacity = noCapacity))
        // Pre-P targets get 0 rather than MIN_VALUE for an unsupported property.
        assertNull(batteryPercentOf(level = -1, scale = -1, capacity = 0))
    }

    @Test
    fun `a genuine zero with no capacity to contradict it stays zero`() {
        assertEquals(0, batteryPercentOf(level = 0, scale = 100, capacity = noCapacity))
    }

    @Test
    fun `a full battery reads 100`() {
        assertEquals(100, batteryPercentOf(level = 100, scale = 100, capacity = 100))
    }
}
