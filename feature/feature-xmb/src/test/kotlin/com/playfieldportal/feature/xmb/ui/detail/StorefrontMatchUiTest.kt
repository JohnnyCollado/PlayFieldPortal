package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.feature.artwork.match.MatchConfidence
import com.playfieldportal.feature.artwork.match.MatchSignal
import com.playfieldportal.feature.artwork.match.ScoredStorefrontCandidate
import com.playfieldportal.feature.artwork.match.Storefront
import com.playfieldportal.feature.artwork.match.StorefrontCandidate
import com.playfieldportal.feature.artwork.match.StorefrontMatchRepository
import com.playfieldportal.feature.artwork.match.StorefrontIdentityRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning matcher types into screen text (C23 T6, Phases 10 and 18).
 *
 * Pure mapping, so it needs no ViewModel and no Compose. What it pins is the wording the user
 * actually acts on — above all that a signal which was NOT compared still gets a line, because a
 * total with no visible reasoning is a number the user is asked to trust blindly.
 */
class StorefrontMatchUiTest {

    private fun scored(
        id: String = "379720",
        title: String = "DOOM",
        year: Int? = null,
        developer: String? = null,
        publisher: String? = null,
        vararg signals: MatchSignal,
    ) = ScoredStorefrontCandidate(
        StorefrontCandidate(Storefront.STEAM, id, title, year, developer, publisher),
        signals.toList(),
    )

    @Test
    fun `a candidate becomes a row with the store's own word for its id`() {
        val row = storefrontRowOf(
            scored(
                year = 2016,
                developer = "id Software",
                publisher = "Bethesda Softworks",
                signals = arrayOf(MatchSignal.EXACT_TITLE, MatchSignal.PUBLISHER),
            )
        )

        assertEquals("appid 379720", row.idLabel)
        assertEquals("2016  ·  id Software  ·  Bethesda Softworks", row.subtitle)
        assertEquals(78, row.score)
        assertTrue(row.exactTitle)
    }

    @Test
    fun `a store that returned only a name produces no subtitle rather than an empty one`() {
        val row = storefrontRowOf(scored(signals = arrayOf(MatchSignal.EXACT_TITLE)))

        assertNull(row.subtitle)
    }

    @Test
    fun `one publisher that is also the developer is not printed twice`() {
        val row = storefrontRowOf(
            scored(developer = "id Software", publisher = "id Software", signals = arrayOf(MatchSignal.EXACT_TITLE))
        )

        assertEquals("id Software", row.subtitle)
    }

    @Test
    fun `only strong signals become chips`() {
        val row = storefrontRowOf(
            scored(signals = arrayOf(MatchSignal.EXACT_TITLE, MatchSignal.PARTIAL_TITLE))
        )

        // A "Partial title" chip would dress a weak match up as evidence. The full ledger is one
        // button away for anyone who wants it.
        assertEquals(listOf("Exact title"), row.strongSignals)
    }

    @Test
    fun `the ledger says what was NOT compared, and why the total looks the way it does`() {
        val row = storefrontRowOf(
            scored(publisher = "Bethesda Softworks", signals = arrayOf(MatchSignal.EXACT_TITLE, MatchSignal.PUBLISHER))
        )

        val counted = row.signalLines.filter { it.counted }
        assertEquals(listOf("+60", "+18"), counted.map { it.points })

        val absent = row.signalLines.filterNot { it.counted }
        // Worded by which side was silent: this store returned no year and no developer, so the
        // line must not tell the user their own copy is the thing missing them.
        assertTrue(absent.any { it.label == "Release year — the store didn't say" })
        assertTrue(absent.any { it.label == "Developer — the store didn't say" })
        assertTrue(absent.all { it.points == "—" })
    }

    @Test
    fun `a signal the store supplied but that did not agree is worded as such`() {
        val row = storefrontRowOf(
            scored(developer = "Nightdive Studios", signals = arrayOf(MatchSignal.EXACT_TITLE))
        )

        assertTrue(row.signalLines.any { it.label == "Developer — no match against your copy" })
    }

    @Test
    fun `a negative signal is shown with its sign, not hidden`() {
        val row = storefrontRowOf(
            scored(year = 2023, signals = arrayOf(MatchSignal.EXACT_TITLE, MatchSignal.RELEASE_YEAR_CONFLICT))
        )

        assertTrue(row.signalLines.any { it.counted && it.points == "-40" })
    }

    // -- The picker's own state ------------------------------------------------

    @Test
    fun `two exact-title rows is the tie the picker explains`() {
        val ui = StorefrontMatchUi(
            loading = false,
            rows = listOf(
                storefrontRowOf(scored("379720", signals = arrayOf(MatchSignal.EXACT_TITLE))),
                storefrontRowOf(scored("2280", signals = arrayOf(MatchSignal.EXACT_TITLE))),
            ),
        )

        assertTrue(ui.tiedOnExactTitle)
        // Candidates plus "No correct match", which is never absent.
        assertEquals(3, ui.stopCount)
        assertEquals(2, ui.noMatchIndex)
    }

    @Test
    fun `one exact row and one partial is not a tie`() {
        val ui = StorefrontMatchUi(
            loading = false,
            rows = listOf(
                storefrontRowOf(scored("379720", signals = arrayOf(MatchSignal.EXACT_TITLE))),
                storefrontRowOf(scored("2300", title = "DOOM II", signals = arrayOf(MatchSignal.PARTIAL_TITLE))),
            ),
        )

        assertFalse(ui.tiedOnExactTitle)
    }

    @Test
    fun `an empty picker still offers the escape hatch`() {
        val ui = StorefrontMatchUi(loading = false)

        assertEquals(0, ui.noMatchIndex)
        assertEquals(1, ui.stopCount)
        assertNull(ui.focusedCandidate)
    }

    // -- More than one store ---------------------------------------------------

    private fun tab(store: Storefront, confidence: MatchConfidence, vararg ids: String) = StorefrontStoreTab(
        store = store,
        label = store.label,
        confidence = confidence,
        rows = ids.map { id ->
            storefrontRowOf(
                ScoredStorefrontCandidate(StorefrontCandidate(store, id, "DOOM"), listOf(MatchSignal.EXACT_TITLE))
            )
        },
    )

    private val twoStores = StorefrontMatchUi(loading = false, gameTitle = "DOOM").withStores(
        listOf(
            tab(Storefront.STEAM, MatchConfidence.AMBIGUOUS, "379720", "2280"),
            tab(Storefront.GOG, MatchConfidence.EXACT, "1390579243"),
        )
    )

    @Test
    fun `the picker opens on the first store and carries the rest`() {
        assertEquals("Steam", twoStores.storeLabel)
        assertEquals(MatchConfidence.AMBIGUOUS, twoStores.confidence)
        assertEquals(listOf("379720", "2280"), twoStores.rows.map { it.storeId })
        assertEquals(2, twoStores.stores.size)
        assertTrue(twoStores.hasOtherStores)
    }

    @Test
    fun `showing another store swaps the rows, the label and the confidence, and resets the cursor`() {
        val onGog = twoStores.copy(focus = 1).showingStore(1)

        assertEquals("GOG", onGog.storeLabel)
        assertEquals(MatchConfidence.EXACT, onGog.confidence)
        assertEquals(listOf("1390579243"), onGog.rows.map { it.storeId })
        assertEquals(0, onGog.focus)
        assertEquals(1, onGog.storeIndex)
    }

    @Test
    fun `a store index past either end stays on the nearest store`() {
        assertEquals("Steam", twoStores.showingStore(-1).storeLabel)
        assertEquals("GOG", twoStores.showingStore(5).storeLabel)
    }

    @Test
    fun `a store that has been answered leaves the picker on the ones still waiting`() {
        val afterSteam = twoStores.withoutStore(Storefront.STEAM)

        assertEquals(listOf("GOG"), afterSteam.stores.map { it.label })
        assertEquals("GOG", afterSteam.storeLabel)
        assertFalse(afterSteam.hasOtherStores)
        // And with the last one answered there is nothing left to show.
        assertTrue(afterSteam.withoutStore(Storefront.GOG).stores.isEmpty())
    }

    @Test
    fun `one store is no tabs at all`() {
        val one = StorefrontMatchUi(loading = false).withStores(listOf(tab(Storefront.STEAM, MatchConfidence.LOW, "1")))

        assertFalse(one.hasOtherStores)
        assertNull(storefrontOtherStoresNote(one))
    }

    @Test
    fun `stores that did not need a choice are named, unreachable ones first`() {
        assertEquals(
            "GOG didn't answer, so this list is Steam's alone. Try again later for GOG.",
            storefrontOtherStoresNote(
                StorefrontMatchUi(loading = false, unavailableStores = listOf("GOG"))
                    .withStores(listOf(tab(Storefront.STEAM, MatchConfidence.LOW, "1")))
            ),
        )
        assertEquals(
            "Already linked on GOG. That link is not changed here.",
            storefrontOtherStoresNote(
                StorefrontMatchUi(loading = false, settledStores = listOf("GOG"))
                    .withStores(listOf(tab(Storefront.STEAM, MatchConfidence.LOW, "1")))
            ),
        )
    }

    // -- A typed search --------------------------------------------------------

    @Test
    fun `a typed name that finds nothing says so by name and points back to the bar`() {
        val (headline, detail) = storefrontEmptyMessage(
            StorefrontMatchUi(loading = false, gameTitle = "Brave Default FF", typedQuery = "Bravely Defalt")
        )

        assertEquals("No store has “Bravely Defalt”.", headline)
        // Not "edit the title": the search bar exists so the title never has to change.
        assertEquals("Nothing was changed, and nothing is linked. Go back to try another name.", detail)
    }

    @Test
    fun `an unreachable store outranks a typed name that found nothing`() {
        val (headline, _) = storefrontEmptyMessage(
            StorefrontMatchUi(loading = false, typedQuery = "Quake", unavailableStores = listOf("Steam"))
        )

        assertEquals("Steam didn't answer.", headline)
    }

    @Test
    fun `while the search bar holds the cursor no store row is the focused one`() {
        val row = storefrontRematchRowOf(repoRow(Storefront.STEAM))

        assertNull(StorefrontRematchUi(loading = false, rows = listOf(row), queryFocused = true).focusedRow)
        assertEquals(row, StorefrontRematchUi(loading = false, rows = listOf(row), queryFocused = false).focusedRow)
    }

    // -- Rematch rows ----------------------------------------------------------

    private fun repoRow(
        store: Storefront,
        identity: StorefrontIdentityRecord? = null,
        searchable: Boolean = true,
    ) = StorefrontMatchRepository.RematchRow(
        store = store,
        identity = identity?.let { StorefrontMatchRepository.LinkedIdentity(it, store.label) },
        searchable = searchable,
    )

    @Test
    fun `a linked store offers Replace and Remove`() {
        val row = storefrontRematchRowOf(
            repoRow(
                Storefront.STEAM,
                StorefrontIdentityRecord(Storefront.STEAM, "379720", userConfirmed = true, resolvedTitle = "DOOM"),
            )
        )

        assertEquals(listOf(RematchAction.REPLACE, RematchAction.REMOVE), row.actions)
        assertEquals("DOOM  ·  appid 379720", row.detail)
        assertTrue(row.userConfirmed)
        // Remove is never the pre-selected action on a row the user just arrived at.
        assertEquals(RematchAction.REPLACE, row.selectedAction)
    }

    @Test
    fun `an unlinked but searchable store offers Search`() {
        val row = storefrontRematchRowOf(repoRow(Storefront.STEAM))

        assertEquals(listOf(RematchAction.SEARCH), row.actions)
        assertEquals("Not linked", row.detail)
        assertTrue(row.enabled)
    }

    @Test
    fun `a store with no provider says so instead of offering a dead button`() {
        val row = storefrontRematchRowOf(repoRow(Storefront.GOG, searchable = false))

        assertFalse(row.enabled)
        // Said as what it is, not as a promise: there may never be a way to search this store.
        assertEquals("Can't be searched — this store has no public catalog to ask", row.note)
    }

    @Test
    fun `an automatic link is labelled as one`() {
        val row = storefrontRematchRowOf(
            repoRow(
                Storefront.STEAM,
                StorefrontIdentityRecord(Storefront.STEAM, "620", userConfirmed = false, resolvedTitle = "Portal 2"),
            )
        )

        assertFalse(row.userConfirmed)
        assertEquals("Matched automatically", row.note)
    }
}
