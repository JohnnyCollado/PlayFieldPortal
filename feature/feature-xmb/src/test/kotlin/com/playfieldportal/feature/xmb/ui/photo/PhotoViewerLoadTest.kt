package com.playfieldportal.feature.xmb.ui.photo

import com.playfieldportal.core.domain.model.Photo
import com.playfieldportal.core.domain.repository.PhotoRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The photo viewer shows the library it was opened for, however the loads finish.
 *
 * Survey L13 (docs/audits/codebase-survey-2026-10-22.md): same unguarded reuse as App Detail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoViewerLoadTest {

    private val dispatcher = StandardTestDispatcher()
    private val photos = mockk<PhotoRepository>(relaxed = true)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun photo(id: String, lib: String) = Photo(id = id, libraryId = lib, uri = "content://p/$id", displayName = "$id.jpg")

    @Test
    fun `a slower load for the previous library never replaces the newer one`() = runTest(dispatcher) {
        val slowOld = CompletableDeferred<List<Photo>>()
        coEvery { photos.getPhotosForLibrary("old") } coAnswers { slowOld.await() }
        coEvery { photos.getPhotosForLibrary("new") } returns listOf(photo("n1", "new"))
        val vm = PhotoViewerViewModel(mockk(relaxed = true), photos, mockk(relaxed = true), mockk(relaxed = true))

        vm.load("o1", "old")
        dispatcher.scheduler.advanceUntilIdle()
        vm.load("n1", "new")
        dispatcher.scheduler.advanceUntilIdle()
        slowOld.complete(listOf(photo("o1", "old")))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("n1"), vm.uiState.value.photos.map { it.id })
    }
}
