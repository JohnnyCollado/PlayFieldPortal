package com.playfieldportal.feature.artwork.api

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow

/** A folder state that is writable: new artwork can be saved. */
val readyFolder = ArtworkFolderState.Ready("content://tree/primary%3AArtwork", "Artwork")

/** A folder whose grant is gone: new writes are paused. */
val unavailableFolder = ArtworkFolderState.Unavailable("content://tree/primary%3AArtwork", "Artwork")

/**
 * A mocked [ArtworkFolderStatus] driven by [state]. `refresh()` publishes nothing and returns the
 * current value, so a test flips [state] to model the grant being lost or the folder returning.
 */
fun folderStatusOf(
    state: MutableStateFlow<ArtworkFolderState> = MutableStateFlow(readyFolder),
): ArtworkFolderStatus = mockk(relaxed = true) {
    every { this@mockk.state } returns state
    coEvery { refresh() } answers { state.value }
}
