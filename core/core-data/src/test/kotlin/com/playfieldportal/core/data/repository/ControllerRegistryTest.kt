package com.playfieldportal.core.data.repository

import com.playfieldportal.core.domain.model.ControllerDevice
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// State-machine tests for ControllerRegistry — the Android InputDeviceListener is a thin adapter
// over onDeviceAdded/onDeviceRemoved, so the tracking rules are exercised without platform APIs.
class ControllerRegistryTest {

    private val registry = ControllerRegistry(mockk(relaxed = true))

    private fun device(id: Int, name: String = "Pad $id") = ControllerDevice(
        deviceId = id,
        name = name,
        vendorId = 1,
        productId = id,
        isGamepad = true,
        isJoystick = true,
        connectedAtEpochMs = 1_000L + id,
    )

    @Test
    fun `added device appears in connectedControllers`() {
        registry.onDeviceAdded(device(1))
        assertEquals(listOf(device(1)), registry.connectedControllers.value)
    }

    @Test
    fun `removed device leaves connectedControllers`() {
        registry.onDeviceAdded(device(1))
        registry.onDeviceAdded(device(2))
        registry.onDeviceRemoved(1)
        assertEquals(listOf(device(2)), registry.connectedControllers.value)
    }

    @Test
    fun `re-adding an existing device id keeps a single entry`() {
        registry.onDeviceAdded(device(1))
        registry.onDeviceAdded(device(1))
        assertEquals(1, registry.connectedControllers.value.size)
    }

    @Test
    fun `markActive sets lastActiveController and survives later adds`() {
        registry.onDeviceAdded(device(1))
        registry.markActive(1)
        assertEquals(device(1), registry.lastActiveController.value)
        registry.onDeviceAdded(device(2))
        assertEquals(device(1), registry.lastActiveController.value)
    }

    @Test
    fun `markActive for an unknown device is ignored`() {
        registry.onDeviceAdded(device(1))
        registry.markActive(99)
        assertNull(registry.lastActiveController.value)
    }

    @Test
    fun `removing the active device clears lastActiveController`() {
        registry.onDeviceAdded(device(1))
        registry.onDeviceAdded(device(2))
        registry.markActive(2)
        registry.onDeviceRemoved(2)
        assertNull(registry.lastActiveController.value)
    }

    @Test
    fun `removing an inactive device keeps lastActiveController`() {
        registry.onDeviceAdded(device(1))
        registry.onDeviceAdded(device(2))
        registry.markActive(2)
        registry.onDeviceRemoved(1)
        assertEquals(device(2), registry.lastActiveController.value)
    }

    @Test
    fun `second controller can become active without locking to the first`() {
        registry.onDeviceAdded(device(1))
        registry.onDeviceAdded(device(2))
        registry.markActive(1)
        registry.markActive(2)
        assertEquals(device(2), registry.lastActiveController.value)
    }
}
