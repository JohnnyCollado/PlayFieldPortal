package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.feature.artwork.match.MatchProvider
import com.playfieldportal.feature.artwork.match.MetadataApplyPolicy
import com.playfieldportal.feature.artwork.match.MetadataField
import com.playfieldportal.feature.artwork.match.MetadataPreset
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A provider's title against one the user typed.
 *
 * The bug this pins: a hand-set title shadows `scraped_title` on screen, so a provider apply that
 * refreshed the column changed nothing the user could see — and when the column and the provider
 * already agreed, the preview did not even offer the change. Both come from the same place: the
 * row a provider is measured against. It is now the title on screen.
 */
class MetadataTitleReplaceTest {

    private val provider = MetadataPreset(
        provider = MatchProvider.SCREENSCRAPER,
        title = "Crash Bandicoot",
        developer = "Naughty Dog",
    )

    private fun preview(
        typedTitle: String? = null,
        policy: MetadataApplyPolicy = MetadataApplyPolicy.REPLACE_ALL,
    ) = MetadataPreviewUi(
        loading = false,
        editable = true,
        current = mapOf(
            MetadataField.TITLE to "Crash Bandicoot",
            MetadataField.DEVELOPER to "Scraped Studio",
        ),
        effective = mapOf(
            MetadataField.TITLE to (typedTitle ?: "Crash Bandicoot"),
            MetadataField.DEVELOPER to "Scraped Studio",
        ),
        overridden = if (typedTitle != null) setOf(MetadataField.TITLE) else emptySet(),
        presets = listOf(provider),
        policy = policy,
    )

    @Test
    fun `a title the user typed is what the provider row is compared against`() {
        val row = preview(typedTitle = "Crash 1").rows.first { it.field == MetadataField.TITLE }

        // Current shows what they SEE, not the column hiding under it — otherwise the preview
        // would compare against a value the apply no longer leaves in place.
        assertEquals("Crash 1", row.current)
        assertTrue(row.differs)
    }

    @Test
    fun `a provider title matching the stored column still offers the change`() {
        // scraped_title is already "Crash Bandicoot" and so is the provider's. Compared against
        // the column that is no change at all — which is exactly how the typed title got stuck.
        assertTrue(MetadataField.TITLE in preview(typedTitle = "Crash 1").willWrite)
    }

    @Test
    fun `an untouched title still compares against the stored column`() {
        val ui = preview()

        assertEquals("Crash Bandicoot", ui.rows.first { it.field == MetadataField.TITLE }.current)
        assertFalse(MetadataField.TITLE in ui.willWrite)
    }

    @Test
    fun `Fill Missing Only leaves a typed title alone`() {
        // A hand-set title is the opposite of missing, whatever the column underneath holds.
        val ui = preview(typedTitle = "Crash 1", policy = MetadataApplyPolicy.FILL_MISSING_ONLY)

        assertFalse(MetadataField.TITLE in ui.willWrite)
    }

    @Test
    fun `Choose Fields writes the title only when it is ticked`() {
        val ui = preview(typedTitle = "Crash 1", policy = MetadataApplyPolicy.CHOOSE_FIELDS)

        assertFalse(MetadataField.TITLE in ui.copy(chosen = setOf(MetadataField.DEVELOPER)).willWrite)
        assertTrue(MetadataField.TITLE in ui.copy(chosen = setOf(MetadataField.TITLE)).willWrite)
    }
}
