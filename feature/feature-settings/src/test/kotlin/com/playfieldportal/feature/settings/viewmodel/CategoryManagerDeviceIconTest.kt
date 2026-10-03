package com.playfieldportal.feature.settings.viewmodel

import android.net.Uri
import com.playfieldportal.core.data.repository.CategoryRepositoryImpl
import com.playfieldportal.core.data.repository.CollectionRepository
import com.playfieldportal.core.data.repository.CustomIconStore
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import com.playfieldportal.core.ui.icons.CustomIconLimits
import com.playfieldportal.core.ui.icons.UserCategoryIconKeys
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Device images for user categories: choose / replace / remove in both the Change Icon and the
 * create flow, and the row fields the screen reads. The store is mocked — its own behaviour is
 * covered in core-data; here only the view model's sequencing and state are under test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryManagerDeviceIconTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val repo = mockk<CategoryRepositoryImpl>(relaxed = true)
    private val collections = mockk<CollectionRepository>()
    private val store = mockk<CustomIconStore>(relaxed = true)
    private val uri = mockk<Uri>()

    private val id = "custom_ff_5"
    private val key = "usercat_custom_ff_5"

    private fun category(id: String, iconKey: String = "ic_favorites") = Category(
        id = id, name = id, iconKey = iconKey, type = CategoryType.MANUAL, position = 9,
    )

    private fun viewModel(
        categories: List<Category> = listOf(category(id)),
        stored: Set<String> = emptySet(),
        protectedIds: Set<String> = emptySet(),
    ): CategoryManagerViewModel {
        every { repo.observeAll() } returns flowOf(categories)
        every { repo.isProtected(any()) } answers { firstArg<String>() in protectedIds }
        every { collections.observeCollections() } returns flowOf(emptyList())
        every { store.observeStoredKeys() } returns flowOf(stored)
        coEvery { store.import(any(), any(), any()) } returns CustomIconStore.ImportResult(true)
        coEvery { store.move(any(), any()) } returns true
        coEvery { repo.createCustomCategory(any(), any(), any()) } returns "custom_retro_1"
        return CategoryManagerViewModel(repo, collections, store)
    }

    private fun CategoryManagerViewModel.startCreateWithName() {
        startCreate()
        confirmCreateName("Retro")
    }

    // ── V1 ────────────────────────────────────────────────────────────────────────

    @Test
    fun `Change Icon pick imports to the category key and returns to detail`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.openDetail(id)
        vm.startChangeIcon()

        vm.onDeviceImagePicked(uri, "image/png")
        advanceUntilIdle()

        coVerify(exactly = 1) { store.import(key, uri, "image/png") }
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(CategoryStep.DETAIL, vm.uiState.value.step)
        assertNull(vm.uiState.value.message)
        job.cancel()
    }

    @Test
    fun `a rejected pick keeps the picker open, shows the store message and leaves the icon alone`() = runTest(dispatcher) {
        val vm = viewModel()
        coEvery { store.import(any(), any(), any()) } returns
            CustomIconStore.ImportResult(false, CustomIconLimits.MSG_UNSUPPORTED_FORMAT)
        vm.openDetail(id)
        vm.startChangeIcon()

        vm.onDeviceImagePicked(uri, "video/mp4")
        advanceUntilIdle()

        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(CategoryStep.PICK_ICON, vm.uiState.value.step)
        assertEquals(CustomIconLimits.MSG_UNSUPPORTED_FORMAT, vm.uiState.value.message)
        coVerify(exactly = 0) { repo.setIcon(any(), any()) }
        job.cancel()
    }

    // ── V2 ────────────────────────────────────────────────────────────────────────

    @Test
    fun `Change Icon built-in pick sets the icon and clears the image`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.openDetail(id)
        vm.startChangeIcon()

        vm.chooseIcon("ic_favorites")
        advanceUntilIdle()

        coVerify { repo.setIcon(id, "ic_favorites") }
        coVerify { store.clear(key) }
    }

    // ── V3 ────────────────────────────────────────────────────────────────────────

    @Test
    fun `Create with only an image imports to the draft, creates with the games fallback, then moves the draft`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.startCreateWithName()

        vm.onDeviceImagePicked(uri, "image/gif")
        advanceUntilIdle()
        coVerify { store.import(UserCategoryIconKeys.DRAFT_KEY, uri, "image/gif") }
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(CategoryStep.PICK_TYPE, vm.uiState.value.step)

        vm.chooseType(true)
        advanceUntilIdle()

        coVerifyOrder {
            repo.createCustomCategory("Retro", "ic_games", true)
            store.move(UserCategoryIconKeys.DRAFT_KEY, "usercat_custom_retro_1")
        }
        assertEquals(CategoryStep.LIST, vm.uiState.value.step)
        assertFalse(vm.uiState.value.pendingHasImage)
        job.cancel()
    }

    @Test
    fun `Create with a built-in only never moves a draft`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.startCreateWithName()
        vm.chooseIcon("ic_favorites")
        advanceUntilIdle()

        vm.chooseType(false)
        advanceUntilIdle()

        coVerify { repo.createCustomCategory("Retro", "ic_favorites", false) }
        coVerify(exactly = 0) { store.move(any(), any()) }
    }

    // ── V4 ────────────────────────────────────────────────────────────────────────

    @Test
    fun `cancelling the create flow clears the draft`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.startCreateWithName()
        vm.onDeviceImagePicked(uri, "image/png")
        advanceUntilIdle()
        vm.onBack() // PICK_TYPE -> PICK_ICON
        advanceUntilIdle()
        coVerify(exactly = 0) { store.clear(UserCategoryIconKeys.DRAFT_KEY) }

        assertTrue(vm.onBack()) // PICK_ICON -> LIST
        advanceUntilIdle()

        coVerify(exactly = 1) { store.clear(UserCategoryIconKeys.DRAFT_KEY) }
    }

    // ── V5 ────────────────────────────────────────────────────────────────────────

    @Test
    fun `rows report hasImage, a readable icon label and the device image key`() = runTest(dispatcher) {
        val vm = viewModel(
            categories = listOf(
                category(id),
                category("custom_psx_6", iconKey = "ic_ps1"),
                category("custom_prot_7"),
            ),
            stored = setOf(key),
            protectedIds = setOf("custom_prot_7"),
        )
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val rows = vm.uiState.value.categories.associateBy { it.id }
        assertTrue(rows.getValue(id).hasImage)
        assertEquals("Your Image", rows.getValue(id).iconLabel)
        assertEquals(key, rows.getValue(id).deviceImageKey)
        assertFalse(rows.getValue("custom_psx_6").hasImage)
        assertEquals("PlayStation", rows.getValue("custom_psx_6").iconLabel)
        assertNull(rows.getValue("custom_prot_7").deviceImageKey)
        job.cancel()
    }

    @Test
    fun `iconValueLabel names the image or the catalog entry`() {
        assertEquals("Your Image", iconValueLabel("ic_favorites", hasImage = true))
        assertEquals("Favorites", iconValueLabel("ic_favorites", hasImage = false))
    }

    // ── V6 ────────────────────────────────────────────────────────────────────────

    @Test
    fun `Remove Image in Change Icon clears the key and returns to detail`() = runTest(dispatcher) {
        val vm = viewModel(stored = setOf(key))
        vm.openDetail(id)
        vm.startChangeIcon()

        vm.removeDeviceImage()
        advanceUntilIdle()

        coVerify { store.clear(key) }
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(CategoryStep.DETAIL, vm.uiState.value.step)
        job.cancel()
    }

    @Test
    fun `device image actions are no-ops for a protected category`() = runTest(dispatcher) {
        val vm = viewModel(protectedIds = setOf(id))
        vm.openDetail(id)
        vm.startChangeIcon()

        vm.onDeviceImagePicked(uri, "image/png")
        vm.removeDeviceImage()
        advanceUntilIdle()

        coVerify(exactly = 0) { store.import(any(), any(), any()) }
        coVerify(exactly = 0) { store.clear(any()) }
    }

    // ── V7 ────────────────────────────────────────────────────────────────────────

    @Test
    fun `Create picking a built-in after an image clears the draft`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.startCreateWithName()
        vm.onDeviceImagePicked(uri, "image/png")
        advanceUntilIdle()

        vm.chooseIcon("ic_favorites")
        advanceUntilIdle()

        coVerify { store.clear(UserCategoryIconKeys.DRAFT_KEY) }
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertFalse(vm.uiState.value.pendingHasImage)
        job.cancel()
    }

    // ── V8 ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a failed move after create keeps the category and reports it`() = runTest(dispatcher) {
        val vm = viewModel()
        coEvery { store.move(any(), any()) } returns false
        vm.startCreateWithName()
        vm.onDeviceImagePicked(uri, "image/png")
        advanceUntilIdle()

        vm.chooseType(true)
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.createCustomCategory("Retro", "ic_games", true) }
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(CategoryStep.LIST, vm.uiState.value.step)
        assertTrue(vm.uiState.value.message != null)
        job.cancel()
    }
}
