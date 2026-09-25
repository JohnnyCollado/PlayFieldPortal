package com.playfieldportal.feature.artwork.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The aggregate a bulk storefront run reports (C23 T6, Phase 19).
 *
 * Pure arithmetic over resolutions, tested without the tray: what matters is that the counts are
 * honest — above all that a store which could not be reached is never counted as a game the store
 * does not have, because those two lead the user to opposite actions.
 */
class StorefrontMetadataSyncTest {

    private fun resolution(vararg entries: Pair<Storefront, StorefrontMetadataResolver.Resolution>) =
        StorefrontMetadataResolver.GameResolution(gameId = 1L, byStore = entries.toMap())

    private val linked = StorefrontMetadataResolver.Resolution.Linked(
        identity = StorefrontIdentityRecord(Storefront.STEAM, "620"),
        preset = MetadataPreset(provider = MatchProvider.STEAM, title = "Portal 2"),
        newlyLinked = true,
    )

    private val ambiguous = StorefrontMetadataResolver.Resolution.NeedsConfirmation(
        StorefrontMatchResult(Storefront.STEAM, MatchConfidence.AMBIGUOUS, best = null)
    )

    private val down = StorefrontMetadataResolver.Resolution.Unavailable(
        Storefront.STEAM,
        StorefrontOutcome.Failure(StorefrontFailure.RATE_LIMITED),
    )

    @Test
    fun `a matched game counts once, whatever else the other stores said`() {
        val summary = StorefrontMetadataSync.Summary() +
            resolution(Storefront.STEAM to linked, Storefront.GOG to ambiguous)

        assertEquals(1, summary.processed)
        assertEquals(1, summary.matched)
        assertEquals(0, summary.needsConfirmation)
    }

    @Test
    fun `an unreachable store is never counted as a game the store does not have`() {
        val summary = StorefrontMetadataSync.Summary() + resolution(Storefront.STEAM to down)

        assertEquals(0, summary.noMatch)
        assertEquals(setOf(Storefront.STEAM), summary.unavailableStores)
        assertTrue(summary.partial)
    }

    @Test
    fun `a genuine miss is counted as one`() {
        val summary = StorefrontMetadataSync.Summary() +
            resolution(Storefront.STEAM to StorefrontMetadataResolver.Resolution.NoMatch)

        assertEquals(1, summary.noMatch)
        assertFalse(summary.partial)
    }

    @Test
    fun `the tray message is one line and names only what happened`() {
        var summary = StorefrontMetadataSync.Summary()
        repeat(42) { summary += resolution(Storefront.STEAM to linked) }
        repeat(5) { summary += resolution(Storefront.STEAM to ambiguous) }
        repeat(3) { summary += resolution(Storefront.STEAM to StorefrontMetadataResolver.Resolution.NoMatch) }

        assertEquals(50, summary.processed)
        assertEquals(
            "42 matched · 5 need confirmation · 3 with no storefront match",
            summary.message,
        )
    }

    @Test
    fun `a clean run says so without listing zeroes`() {
        var summary = StorefrontMetadataSync.Summary()
        repeat(7) { summary += resolution(Storefront.STEAM to linked) }

        assertEquals("7 matched", summary.message)
    }
}
