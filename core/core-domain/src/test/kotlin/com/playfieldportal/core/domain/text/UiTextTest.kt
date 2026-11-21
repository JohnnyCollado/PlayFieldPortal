package com.playfieldportal.core.domain.text

import android.content.res.Resources
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class UiTextTest {

    private val resources = mockk<Resources> {
        every { getString(1) } returns "Settings"
        every { getString(2, "Doom") } returns "Launching Doom"
        every { getString(2, "Settings") } returns "Launching Settings"
        every { getQuantityString(3, 1, 1) } returns "1 game"
        every { getQuantityString(3, 4, 4) } returns "4 games"
    }

    @Test
    fun `a plain resource resolves to its text`() {
        assertEquals("Settings", UiText.res(1).resolve(resources))
    }

    @Test
    fun `arguments are passed through`() {
        assertEquals("Launching Doom", UiText.res(2, "Doom").resolve(resources))
    }

    @Test
    fun `a UiText argument is resolved before formatting`() {
        assertEquals("Launching Settings", UiText.res(2, UiText.res(1)).resolve(resources))
    }

    @Test
    fun `a plural picks the form for its count`() {
        assertEquals("1 game", UiText.plural(3, 1).resolve(resources))
        assertEquals("4 games", UiText.plural(3, 4).resolve(resources))
    }

    @Test
    fun `raw text is returned as given`() {
        assertEquals("Final Fantasy VII", UiText.raw("Final Fantasy VII").resolve(resources))
    }

    @Test
    fun `joined parts resolve each part`() {
        assertEquals("Settings · Doom", UiText.Joined(listOf(UiText.res(1), UiText.raw("Doom")), " · ").resolve(resources))
    }

    @Test
    fun `equal content compares equal so view model tests can assert on it`() {
        assertEquals(UiText.res(2, "Doom"), UiText.res(2, "Doom"))
    }
}
