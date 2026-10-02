package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.data.repository.CategoryRepositoryImpl
import com.playfieldportal.core.data.repository.CollectionRepository
import com.playfieldportal.core.data.repository.CustomIconStore
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The XMB's category menu opens Category Manager on one category, with Rename or Change Icon already up. */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryManagerTargetTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): CategoryManagerViewModel {
        val repo = mockk<CategoryRepositoryImpl>(relaxed = true)
        every { repo.observeAll() } returns flowOf(
            listOf(Category("custom_ff_5", "Retro Shelf", "ic_favorites", type = CategoryType.MANUAL, position = 9)),
        )
        every { repo.isProtected(any()) } returns false
        val collections = mockk<CollectionRepository>()
        every { collections.observeCollections() } returns flowOf(emptyList())
        val store = mockk<CustomIconStore>(relaxed = true)
        every { store.observeStoredKeys() } returns flowOf(emptySet())
        return CategoryManagerViewModel(repo, collections, store)
    }

    @Test
    fun `Rename opens that category's detail with its rename up`() = runTest(dispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect {} }
        vm.openTarget(CategoryManagerTarget("custom_ff_5", CategoryManagerTargetAction.RENAME))
        val state = vm.uiState.value
        assertEquals(CategoryStep.DETAIL, state.step)
        assertEquals("custom_ff_5", state.detailId)
        assertEquals("custom_ff_5", state.renameTargetId)
    }

    @Test
    fun `Change Icon opens that category's icon picker`() = runTest(dispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect {} }
        vm.openTarget(CategoryManagerTarget("custom_ff_5", CategoryManagerTargetAction.CHANGE_ICON))
        val state = vm.uiState.value
        assertEquals(CategoryStep.PICK_ICON, state.step)
        assertEquals("custom_ff_5", state.detailId)
        assertEquals(false, state.pickingIconForCreate)
        assertNull(state.renameTargetId)
    }
}
