package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Games root's two card menus. They share one shape — Games, Update, Display, Manage — so All
 * Games reads as the whole-library version of a platform card.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryCardContextMenuItemsTest {

    private fun card(platformId: String = "psp", pinned: Boolean = false) =
        platformCardMenuItems(platformId, pinned, iconDisplayLabel = UiText.raw("Global: Box Art"))

    private fun headers(items: List<XMBContextMenuItem>) = items.mapNotNull { it.header.enOrNull() }

    // -- Platform card ---------------------------------------------------------

    @Test
    fun `a console card is eight rows in four groups, Remove last`() {
        val items = card()

        assertEquals(
            listOf(
                "scan_roms", "update_metadata", "scrape_missing_artwork", "icon_display_platform",
                "pin", "library_manager", "hide", "remove",
            ),
            items.map { it.id },
        )
        assertEquals(listOf("Games", "Update", "Display", "Manage"), headers(items))
        assertTrue(items.last().isDestructive)
    }

    @Test
    fun `a header sits on the first row of its group and nowhere else`() {
        val byId = card().associateBy { it.id }

        assertEquals("Games", byId.getValue("scan_roms").header.enOrNull())
        assertEquals("Update", byId.getValue("update_metadata").header.enOrNull())
        assertNull(byId.getValue("scrape_missing_artwork").header.enOrNull())
        assertEquals("Display", byId.getValue("icon_display_platform").header.enOrNull())
        assertNull(byId.getValue("pin").header.enOrNull())
        assertEquals("Manage", byId.getValue("library_manager").header.enOrNull())
        assertNull(byId.getValue("remove").header.enOrNull())
    }

    @Test
    fun `the rows read as agreed`() {
        val byId = card().associateBy { it.id }

        assertEquals("Scan for Games", byId.getValue("scan_roms").label.en())
        assertEquals("Fetch Missing Artwork", byId.getValue("scrape_missing_artwork").label.en())
        assertEquals("Library Manager", byId.getValue("library_manager").label.en())
        assertEquals("Hide Card", byId.getValue("hide").label.en())
        assertEquals("Remove Card", byId.getValue("remove").label.en())
    }

    @Test
    fun `a setting is a value beside its row, never part of the label`() {
        val byId = card().associateBy { it.id }

        assertEquals("Icon Display", byId.getValue("icon_display_platform").label.en())
        assertEquals("Global: Box Art", byId.getValue("icon_display_platform").value.enOrNull())
        assertEquals("Pin to Top", byId.getValue("pin").label.en())
        assertEquals("Off", byId.getValue("pin").value.enOrNull())
    }

    @Test
    fun `a pinned card keeps the row's name and offers the unpin`() {
        val pin = card(pinned = true).single { it.label.en() == "Pin to Top" }

        assertEquals("unpin", pin.id)
        assertEquals("On", pin.value.enOrNull())
    }

    @Test
    fun `the Android card finds games instead of scanning for them`() {
        val items = card(platformId = XMBViewModel.ANDROID_PLATFORM_ID)

        assertEquals("find_games", items.first().id)
        assertEquals("Games", items.first().header.enOrNull())
        assertFalse(items.any { it.id == "scan_roms" })
    }

    @Test
    fun `the Windows card adds the import and the achievement match, and cannot be removed`() {
        val items = card(platformId = XMBViewModel.WINDOWS_PLATFORM_ID)
        val ids = items.map { it.id }

        assertEquals(listOf("scan_roms", "import_pc_games"), ids.take(2))
        assertEquals(ids.indexOf("scrape_missing_artwork") + 1, ids.indexOf("batch_match_local"))
        assertEquals("Match Achievements", items.single { it.id == "batch_match_local" }.label.en())
        assertFalse("remove" in ids)
        // Same four groups: the extra rows join a group, they do not add one.
        assertEquals(listOf("Games", "Update", "Display", "Manage"), headers(items))
    }

    // -- All Games -------------------------------------------------------------

    @Test
    fun `All Games is the same four groups over the whole library`() {
        val items = allGamesMenuItems(iconDisplayLabel = UiText.raw("Box Art"))

        assertEquals(
            listOf(
                "scan_all", "update_metadata", "scrape_missing_artwork", "relink_artwork",
                "icon_display_global", "library_manager",
            ),
            items.map { it.id },
        )
        assertEquals(listOf("Games", "Update", "Display", "Manage"), headers(items))
        assertEquals("Scan All Cards", items.first().label.en())
        assertEquals("Box Art", items.single { it.id == "icon_display_global" }.value.enOrNull())
    }

    @Test
    fun `Import PC Games lives on the Windows card only`() {
        assertFalse(allGamesMenuItems(iconDisplayLabel = UiText.raw("Box Art")).any { it.id == "import_pc_games" })
    }

    @Test
    fun `All Games can remove nothing`() {
        assertFalse(allGamesMenuItems(iconDisplayLabel = UiText.raw("Box Art")).any { it.isDestructive })
    }

    // -- Scan All Cards summary ------------------------------------------------

    @Test
    // Survey U25 (docs/audits/codebase-survey-2026-10-22.md): "across 5 Memory Cards" read as five
    // cards having new games, when 5 was every card scanned. The count now says what it counts.
    fun `the scan summary says what was found and how many cards were scanned`() {
        assertEquals("3 new games · 5 Memory Cards scanned", scanAllSummary(added = 3, cards = 5, failed = 0).en())
        assertEquals("1 new game · 1 Memory Card scanned", scanAllSummary(added = 1, cards = 1, failed = 0).en())
        assertEquals("No new games found", scanAllSummary(added = 0, cards = 5, failed = 0).en())
    }

    @Test
    fun `a card that could not be scanned is named in the summary, never folded into nothing new`() {
        assertEquals(
            "No new games found · 2 cards could not be scanned",
            scanAllSummary(added = 0, cards = 5, failed = 2).en(),
        )
        assertEquals(
            "3 new games · 5 Memory Cards scanned · 1 card could not be scanned",
            scanAllSummary(added = 3, cards = 5, failed = 1).en(),
        )
    }
}
