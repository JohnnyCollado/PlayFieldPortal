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
            ArtworkFolderPrompts.onNeed(unavailable, pending = null, trigger = FolderNeedTrigger.SCRAPE),
        )
    }

    @Test
    fun `storage not reachable yet with a live grant never asks for a relink on launch or scrape`() {
        val mounting = ArtworkFolderState.Unavailable("content://tree/a", "/storage/art", grantLive = true)
        assertNull(ArtworkFolderPrompts.onLaunch(mounting, deferred = false, pending = null))
        assertNull(ArtworkFolderPrompts.onNeed(mounting, pending = null, trigger = FolderNeedTrigger.SCRAPE))
    }

    @Test
    fun `storage not reachable yet still answers a user pick`() {
        val mounting = ArtworkFolderState.Unavailable("content://tree/a", "/storage/art", grantLive = true)
        assertEquals(
            ArtworkFolderPrompt.Unavailable("/storage/art"),
            ArtworkFolderPrompts.onNeed(mounting, pending = null, trigger = FolderNeedTrigger.PICK),
        )
    }

    @Test
    fun `not linked with no internal art is silent on launch and asks to choose on need`() {
        val state = ArtworkFolderState.NotLinked(internalFiles = 0)
        assertNull(ArtworkFolderPrompts.onLaunch(state, deferred = false, pending = null))
        assertEquals(ArtworkFolderPrompt.ChooseFolder, ArtworkFolderPrompts.onNeed(state, pending = null, trigger = FolderNeedTrigger.SCRAPE))
    }

    @Test
    fun `not linked with internal art offers the move on launch and on need`() {
        val state = ArtworkFolderState.NotLinked(internalFiles = 12)
        assertEquals(
            ArtworkFolderPrompt.MoveArtwork(12),
            ArtworkFolderPrompts.onLaunch(state, deferred = false, pending = null),
        )
        assertEquals(ArtworkFolderPrompt.MoveArtwork(12), ArtworkFolderPrompts.onNeed(state, pending = null, trigger = FolderNeedTrigger.SCRAPE))
    }

    @Test
    fun `deferred suppresses the move offer on launch only`() {
        val state = ArtworkFolderState.NotLinked(internalFiles = 12)
        assertNull(ArtworkFolderPrompts.onLaunch(state, deferred = true, pending = null))
        assertEquals(ArtworkFolderPrompt.MoveArtwork(12), ArtworkFolderPrompts.onNeed(state, pending = null, trigger = FolderNeedTrigger.SCRAPE))
    }

    @Test
    fun `ready and unknown never prompt`() {
        for (state in listOf(ready, ArtworkFolderState.Unknown)) {
            assertNull(ArtworkFolderPrompts.onLaunch(state, deferred = false, pending = null))
            assertNull(ArtworkFolderPrompts.onNeed(state, pending = null, trigger = FolderNeedTrigger.SCRAPE))
        }
    }

    @Test
    fun `a pending foreign library beats everything`() {
        val expected = ArtworkFolderPrompt.ForeignLibrary("Other", 7)
        for (state in listOf(ready, unavailable, ArtworkFolderState.Unknown, ArtworkFolderState.NotLinked(3))) {
            assertEquals(expected, ArtworkFolderPrompts.onLaunch(state, deferred = true, pending = foreign))
            assertEquals(expected, ArtworkFolderPrompts.onNeed(state, pending = foreign, trigger = FolderNeedTrigger.SCRAPE))
        }
    }
}
