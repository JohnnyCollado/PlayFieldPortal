package com.playfieldportal.feature.xmb.ui

import com.playfieldportal.feature.xmb.viewmodel.XMBItem
import com.playfieldportal.feature.xmb.viewmodel.XMBItemType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the PSP's read beat on the UMD slot: focusing it keeps the UMD glyph (and no game art)
 * until the disc has been "read", then the inserted game shows like any other focused game.
 */
class UmdSlotReadTest {

    private val umd = XMBItem(
        "umd_slot", "Crisis Core", artworkUri = "bg.png", logoUri = "logo.png",
        type = XMBItemType.UMD_SLOT, gameId = 7L,
    )
    private val game = XMBItem("g", "Patapon", artworkUri = "bg2.png", gameId = 8L)

    @Test
    fun `a focused UMD that has not been read yet shows no game art`() {
        assertNull(umd.afterUmdRead(read = false))
    }

    @Test
    fun `once read the UMD shows its game like any focused game`() {
        assertSame(umd, umd.afterUmdRead(read = true))
    }

    @Test
    fun `an ordinary game row is never held back`() {
        assertSame(game, game.afterUmdRead(read = false))
    }

    @Test
    fun `only a focused UMD slot starts a read`() {
        assertNotNull(umdReadKey(umd, columnIndex = 2))
        assertNull(umdReadKey(game, columnIndex = 2))
        assertNull(umdReadKey(null, columnIndex = 2))
    }

    @Test
    fun `stepping straight to another column's UMD starts a fresh read`() {
        // Every column's slot shares one row id, so the column and the inserted game key the read.
        val other = umd.copy(gameId = 9L)
        assert(umdReadKey(umd, columnIndex = 2) != umdReadKey(other, columnIndex = 3))
        assert(umdReadKey(umd, columnIndex = 2) != umdReadKey(umd, columnIndex = 3))
    }

    // ── The title beside ICON0 once the disc is read ──────────────────────────

    private val logoless = umd.copy(logoUri = null)

    @Test
    fun `the UMD glyph is always named`() {
        assertTrue(xmbRowShowsTitle(umd, isSelected = true, umdShowsGame = false, moving = false))
        assertTrue(xmbRowShowsTitle(logoless, isSelected = true, umdShowsGame = false, moving = false))
    }

    @Test
    fun `a read game with a logo is bare - the logo is its name`() {
        assertFalse(xmbRowShowsTitle(umd, isSelected = true, umdShowsGame = true, moving = false))
    }

    @Test
    fun `a read game with no logo shows its title beside ICON0, like a hot list row`() {
        assertTrue(xmbRowShowsTitle(logoless, isSelected = true, umdShowsGame = true, moving = false))
        val hotListRow = XMBItem("g", "Patapon", gameId = 8L, isRealGame = true)
        assertTrue(xmbRowShowsTitle(hotListRow, isSelected = true, umdShowsGame = false, moving = false))
    }

    @Test
    fun `game rows keep their rules`() {
        val withLogo = XMBItem("g", "Patapon", logoUri = "logo.png", gameId = 8L, isRealGame = true)
        assertFalse(xmbRowShowsTitle(withLogo, isSelected = true, umdShowsGame = false, moving = false))
        assertFalse(xmbRowShowsTitle(withLogo.copy(logoUri = null), isSelected = false, umdShowsGame = false, moving = false))
        assertTrue(xmbRowShowsTitle(withLogo, isSelected = false, umdShowsGame = false, moving = true))
    }
}
