package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GamepadAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The helper footer is the page's only controller documentation, so what it claims must follow the
 * overlay that actually owns input. [gameDetailHelperItems] is a pure function of the state for
 * exactly this reason: the footer's contract is testable without a composition.
 */
class GameDetailHelperFooterTest {

    private val baseState = GameDetailUiState(isLoading = false, game = Game(id = 1L, title = "Crash Bandicoot", platformId = "psx"))

    private fun labels(state: GameDetailUiState) =
        gameDetailHelperItems(state).flatMap { item -> listOf(item.label) }

    @Test
    fun `the base page documents Confirm, Options and Back`() {
        val items = gameDetailHelperItems(baseState)

        assertEquals(listOf("Launch", "Options", "Back"), labels(baseState))
        assertEquals(
            "the footer names the actions, so it can never disagree with the pad",
            listOf(
                listOf(GamepadAction.SELECT),
                listOf(GamepadAction.OPEN_CONTEXT_MENU),
                listOf(GamepadAction.BACK),
            ),
            items.map { it.actions },
        )
    }

    @Test
    fun `the title confirm owns the hints while it is up`() {
        val state = baseState.copy(
            metadataPreview = MetadataPreviewUi(
                titleReplace = TitleReplaceConfirm(current = "Crash 1", incoming = "Crash Bandicoot"),
            ),
        )

        assertEquals(listOf("Replace", "Keep Mine"), labels(state))
    }

    @Test
    fun `the metadata overlay replaces the page hints instead of adding to them`() {
        val state = baseState.copy(metadataPreview = MetadataPreviewUi())

        assertEquals(
            listOf("Navigate", "Apply / Toggle", "Policy", "Source", "Close"),
            labels(state),
        )
        assertTrue("no page hint may survive an overlay", "Launch" !in labels(state))
    }

    @Test
    fun `the removal prompt says Remove and Cancel`() {
        val state = baseState.copy(confirmRemove = true)

        assertEquals(listOf("Remove", "Cancel"), labels(state))
    }

    @Test
    fun `the viewers say Close`() {
        assertEquals(listOf("Close", "Back"), labels(baseState.copy(showVideoPlayer = true)))
        assertEquals(listOf("Close", "Back"), labels(baseState.copy(imageViewerUri = "/tmp/a.png")))
    }

    @Test
    fun `the manual viewer documents its own paging`() {
        val state = baseState.copy(manualViewerUri = "/tmp/manual.pdf", manualPageCount = 4)

        assertEquals(listOf("Scroll", "Prev page", "Next page", "Close"), labels(state))
    }

    @Test
    fun `Confirm is named for the node it would activate`() {
        val mediaState = baseState.copy(detailMedia = listOf(DetailMedia("/tmp/clip.mp4", isVideo = true)))

        assertEquals(
            "Play",
            gameDetailHelperItems(mediaState.copy(navFocusKey = GameDetailKeys.media(mediaStableId(mediaState.detailMedia[0]))))
                .first().label,
        )
        assertEquals(
            "Preview",
            gameDetailHelperItems(
                baseState.copy(
                    detailMedia = listOf(DetailMedia("/tmp/shot.png", isVideo = false)),
                    navFocusKey = GameDetailKeys.media("i:/tmp/shot.png"),
                ),
            ).first().label,
        )
        assertEquals(
            "Favorite",
            gameDetailHelperItems(baseState.copy(navFocusKey = GameDetailKeys.FAVORITE)).first().label,
        )
        assertEquals(
            "Options",
            gameDetailHelperItems(baseState.copy(navFocusKey = GameDetailKeys.OPTIONS_ACTION)).first().label,
        )
        assertEquals(
            "Read more",
            gameDetailHelperItems(baseState.copy(navFocusKey = GameDetailKeys.OVERVIEW)).first().label,
        )
    }
}
