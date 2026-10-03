package com.playfieldportal.feature.xmb.ui

import com.playfieldportal.feature.xmb.viewmodel.XMBItem
import com.playfieldportal.feature.xmb.viewmodel.XMBItemType
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
}
