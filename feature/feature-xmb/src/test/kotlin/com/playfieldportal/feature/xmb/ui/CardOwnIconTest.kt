package com.playfieldportal.feature.xmb.ui

import androidx.compose.ui.graphics.ImageBitmap
import com.playfieldportal.core.ui.icons.CustomIcon
import com.playfieldportal.core.ui.icons.XmbIcons
import com.playfieldportal.feature.xmb.viewmodel.XMBItem
import com.playfieldportal.feature.xmb.viewmodel.XMBItemType
import io.mockk.mockk
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** A custom memory card's row draws the card's own image when it has one. */
class CardOwnIconTest {

    private val mine = CustomIcon.Animated("/files/custom-icons/usercard_4.gif", mockk<ImageBitmap>(relaxed = true))

    @Test
    fun `a card with its own image draws it`() {
        val card = XMBItem(id = "col_4", title = "RPGs", collectionId = 4L, type = XMBItemType.COLLECTION)
        assertSame(mine, cardOwnIcon(card, XmbIcons(user = mapOf("usercard_4" to mine))))
    }

    @Test
    fun `a card without one, or a row that is not a card, draws as before`() {
        val card = XMBItem(id = "col_4", title = "RPGs", collectionId = 4L, type = XMBItemType.COLLECTION)
        assertNull(cardOwnIcon(card, XmbIcons()))
        val game = XMBItem(id = "g4", title = "G", gameId = 4L)
        assertNull(cardOwnIcon(game, XmbIcons(user = mapOf("usercard_4" to mine))))
    }
}
