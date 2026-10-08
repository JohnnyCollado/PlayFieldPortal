package com.playfieldportal.feature.artwork.api

import com.playfieldportal.feature.artwork.api.LibraryLinkDecision.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryLinkDecisionTest {

    @Test
    fun `no manifest is a new library`() {
        assertEquals(Outcome.NEW_LIBRARY, LibraryLinkDecision.decide(storedUuid = null, manifestUuid = null))
        assertEquals(Outcome.NEW_LIBRARY, LibraryLinkDecision.decide(storedUuid = "a", manifestUuid = null))
    }

    @Test
    fun `matching uuids are the same library`() {
        assertEquals(Outcome.SAME_LIBRARY, LibraryLinkDecision.decide("a", "a"))
    }

    @Test
    fun `manifest with nothing stored is adopted`() {
        assertEquals(Outcome.ADOPT, LibraryLinkDecision.decide(storedUuid = null, manifestUuid = "a"))
        assertEquals(Outcome.ADOPT, LibraryLinkDecision.decide(storedUuid = " ", manifestUuid = "a"))
    }

    @Test
    fun `different uuids are foreign`() {
        assertEquals(Outcome.FOREIGN, LibraryLinkDecision.decide("a", "b"))
    }
}
