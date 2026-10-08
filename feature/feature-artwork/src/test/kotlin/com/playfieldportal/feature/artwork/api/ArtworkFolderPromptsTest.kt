package com.playfieldportal.feature.artwork.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkFolderPromptsTest {

    private val unavailable = ArtworkFolderState.Unavailable("content://tree/a", "/storage/art")
    private val ready = ArtworkFolderState.Ready("content://tree/a", "/storage/art")
    private val foreign = ForeignLibrary("content://tree/b", "Other", fileCount = 7)

    @Test
    fun `unavailable and not deferred prompts on launch`() {
        assertEquals(
            ArtworkFolderPrompt.Unavailable("/storage/art"),
            ArtworkFolderPrompts.onLaunch(unavailable, deferred = false, pending = null),
        )
    }

    @Test
    fun `deferred suppresses launch prompt but a need still prompts`() {
        assertNull(ArtworkFolderPrompts.onLaunch(unavailable, deferred = true, pending = null))
        assertEquals(
            ArtworkFolderPrompt.Unavailable("/storage/art"),
            ArtworkFolderPrompts.onNeed(unavailable, pending = null),
        )
    }

    @Test
    fun `not linked with no internal art is silent on launch and asks to choose on need`() {
        val state = ArtworkFolderState.NotLinked(internalFiles = 0)
        assertNull(ArtworkFolderPrompts.onLaunch(state, deferred = false, pending = null))
        assertEquals(ArtworkFolderPrompt.ChooseFolder, ArtworkFolderPrompts.onNeed(state, pending = null))
    }

    @Test
    fun `not linked with internal art offers the move on launch and on need`() {
        val state = ArtworkFolderState.NotLinked(internalFiles = 12)
        assertEquals(
            ArtworkFolderPrompt.MoveArtwork(12),
            ArtworkFolderPrompts.onLaunch(state, deferred = false, pending = null),
        )
        assertEquals(ArtworkFolderPrompt.MoveArtwork(12), ArtworkFolderPrompts.onNeed(state, pending = null))
    }

    @Test
    fun `deferred suppresses the move offer on launch only`() {
        val state = ArtworkFolderState.NotLinked(internalFiles = 12)
        assertNull(ArtworkFolderPrompts.onLaunch(state, deferred = true, pending = null))
        assertEquals(ArtworkFolderPrompt.MoveArtwork(12), ArtworkFolderPrompts.onNeed(state, pending = null))
    }

    @Test
    fun `ready and unknown never prompt`() {
        for (state in listOf(ready, ArtworkFolderState.Unknown)) {
            assertNull(ArtworkFolderPrompts.onLaunch(state, deferred = false, pending = null))
            assertNull(ArtworkFolderPrompts.onNeed(state, pending = null))
        }
    }

    @Test
    fun `a pending foreign library beats everything`() {
        val expected = ArtworkFolderPrompt.ForeignLibrary("Other", 7)
        for (state in listOf(ready, unavailable, ArtworkFolderState.Unknown, ArtworkFolderState.NotLinked(3))) {
            assertEquals(expected, ArtworkFolderPrompts.onLaunch(state, deferred = true, pending = foreign))
            assertEquals(expected, ArtworkFolderPrompts.onNeed(state, pending = foreign))
        }
    }
}
