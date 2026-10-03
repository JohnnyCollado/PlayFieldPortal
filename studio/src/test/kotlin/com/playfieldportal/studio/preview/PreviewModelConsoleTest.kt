package com.playfieldportal.studio.preview

import androidx.compose.ui.graphics.ImageBitmap
import com.playfieldportal.studio.StudioState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PreviewModelConsoleTest {

    @Test
    fun `console overrides reach the preview alongside icon overrides`() {
        val icon = ImageBitmap(2, 2)
        val console = ImageBitmap(2, 2)
        val model = StudioState(
            iconBitmaps = mapOf("item_add" to icon),
            sysiconBitmaps = mapOf("sysicon_ps3" to console),
        ).toPreviewModel()
        assertSame(icon, model.iconOverrides["item_add"])
        assertSame(console, model.iconOverrides["sysicon_ps3"])
    }

    @Test
    fun `sample game cards use console keys and their games draw letter tiles`() {
        val root = SampleContent.rootRows(SampleContent.SELECTED_CATEGORY)
        assertEquals(
            listOf(null, "sysicon_allgames", "sysicon_favorites", "item_memcard_games", "sysicon_ps3", "sysicon_psp", "sysicon_windows"),
            root.map { it.slotKey },
        )
        val games = root.flatMap { it.children }
        assertTrue(games.isNotEmpty())
        // Game tiles are not a themeable slot: no key, the ICON0 tile in the platform's accent.
        assertTrue(games.all { it.isGame && it.slotKey == null && it.leading == SampleContent.Leading.GAME && it.accentArgb != null })
        // All Games lists every console's games.
        val consoles = root.filter { it.menu == RowKind.CONSOLE }
        assertEquals(consoles.sumOf { it.children.size }, root.single { it.menu == RowKind.ALL_GAMES }.children.size)
    }
}
