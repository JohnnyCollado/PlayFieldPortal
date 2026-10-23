package com.playfieldportal.feature.settings.viewmodel

import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import com.playfieldportal.core.data.repository.CategoryRepositoryImpl
import com.playfieldportal.core.data.repository.CollectionRepository
import com.playfieldportal.core.data.repository.CustomIconStore
import com.playfieldportal.core.data.repository.ThemeTiers
import com.playfieldportal.core.ui.icons.CustomIcon
import com.playfieldportal.themekit.PtfIcons
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A custom memory card's icon can be the user's own image (still or animated), one of the applied
 * theme's icons, or a built-in — like a category's. Only one is ever active.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardIconViewModelTest {

    @get:Rule val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val collections = mockk<CollectionRepository>(relaxed = true) {
        every { observeCollections() } returns emptyFlow()
    }
    private val categories = mockk<CategoryRepositoryImpl> { every { observeAll() } returns flowOf(emptyList()) }
    private val store = mockk<CustomIconStore>(relaxed = true) {
        every { observeStoredKeys() } returns flowOf(emptySet())
        coEvery { import(any(), any(), any()) } returns CustomIconStore.ImportResult(true)
    }
    private val tiers = mockk<ThemeTiers>(relaxed = true)

    private fun vm() = CollectionsSettingsViewModel(collections, categories, store, tiers)

    @Test
    fun `your own image is imported under the card's key`() = runTest(dispatcher) {
        val uri = mockk<Uri>()
        vm().importCardImage(collectionId = 7L, uri = uri, mime = "image/gif")
        coVerify { store.import("usercard_7", uri, "image/gif") }
    }

    @Test
    fun `a rejected image says why`() = runTest(dispatcher) {
        coEvery { store.import(any(), any(), any()) } returns CustomIconStore.ImportResult(false, "Too many frames")
        val vm = vm()
        vm.importCardImage(7L, mockk(), "image/gif")
        assertEquals("Too many frames", vm.iconMessage.value)
    }

    @Test
    fun `choosing a built-in clears your own image, so only one choice is active`() = runTest(dispatcher) {
        vm().setIcon(7L, "ic_star")
        coVerify { collections.setIcon(7L, "ic_star") }
        coVerify { store.clear("usercard_7") }
    }

    @Test
    fun `removing the image goes back to the built-in choice`() = runTest(dispatcher) {
        vm().removeCardImage(7L)
        coVerify { store.clear("usercard_7") }
    }

    @Test
    fun `deleting a card deletes its image`() = runTest(dispatcher) {
        vm().delete(7L)
        coVerify { collections.delete(7L) }
        coVerify { store.clear("usercard_7") }
    }

    @Test
    fun `a theme icon is copied into the card's own slot`() = runTest(dispatcher) {
        val file = tmp.newFile("catbar_games.png")
        // android.net.Uri is not available in a plain JVM test; stub its file factory.
        io.mockk.mockkStatic(Uri::class)
        every { Uri.fromFile(any()) } returns mockk(relaxed = true)
        vm().chooseThemeIcon(7L, file)
        io.mockk.unmockkStatic(Uri::class)
        coVerify { store.import("usercard_7", any(), "image/png") }
    }

    @Test
    fun `the applied theme's icons are offered, same images once`() {
        val bmp = mockk<ImageBitmap>(relaxed = true)
        val a = tmp.newFile("catbar_games.png").apply { writeBytes(byteArrayOf(1)) }
        val b = tmp.newFile("catbar_music.png").apply { writeBytes(byteArrayOf(2)) }
        val dupe = tmp.newFile("catbar_video.png").apply { writeBytes(byteArrayOf(2)) }
        val ptf = tmp.newFile("2_5.png").apply { writeBytes(byteArrayOf(3)) }

        val rows = cardThemeIconRows(
            slotIcons = mapOf(
                "catbar_games" to (a to CustomIcon.Still(bmp)),
                "catbar_music" to (b to CustomIcon.Still(bmp)),
                "catbar_video" to (dupe to CustomIcon.Still(bmp)),
            ),
            ptfIcons = mapOf(PtfIcons.SlotRef(2, 5) to (ptf to CustomIcon.Still(bmp))),
        )

        assertEquals(3, rows.size)
        assertEquals(setOf(a, b, ptf), rows.map { it.file }.toSet())
    }
}
