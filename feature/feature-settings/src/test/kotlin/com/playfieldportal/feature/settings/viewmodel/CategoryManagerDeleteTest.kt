package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.data.repository.CategoryRepositoryImpl
import com.playfieldportal.core.data.repository.CollectionRepository
import com.playfieldportal.core.data.repository.CollectionsOnDelete
import com.playfieldportal.core.data.repository.CustomIconStore
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import com.playfieldportal.core.domain.model.GameCollection
import io.mockk.coEvery
import io.mockk.coVerify
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

/**
 * Deleting a category from the Category Manager. The screen asks what to do with the category's
 * custom memory cards only when it has some, so the view model has to report how many it holds
 * and pass the user's answer through unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryManagerDeleteTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val repo = mockk<CategoryRepositoryImpl>(relaxed = true)
    private val collections = mockk<CollectionRepository>()
    private val store = mockk<CustomIconStore>(relaxed = true)

    private fun category(id: String, gaming: Boolean) = Category(
        id = id, name = id, iconKey = "ic_games", type = CategoryType.MANUAL, position = 9, isGamingCategory = gaming,
    )

    private fun card(id: Long, categoryId: String) = GameCollection(id = id, name = "Card $id", categoryId = categoryId)

    private fun viewModel(): CategoryManagerViewModel {
        every { repo.observeAll() } returns flowOf(
            listOf(category("custom_ff_5", gaming = true), category("custom_stream_6", gaming = false)),
        )
        every { repo.isProtected(any()) } returns false
        every { collections.observeCollections() } returns flowOf(
            listOf(card(1, "custom_ff_5"), card(2, "custom_ff_5"), card(3, "games")),
        )
        coEvery { repo.delete(any(), any()) } returns true
        every { store.observeStoredKeys() } returns flowOf(emptySet())
        return CategoryManagerViewModel(repo, collections, store)
    }

    @Test
    fun `each category reports how many custom memory cards it holds`() = runTest(dispatcher) {
        val vm = viewModel()
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val rows = vm.uiState.value.categories.associateBy { it.id }
        assertEquals(2, rows.getValue("custom_ff_5").customCardCount)
        assertEquals(0, rows.getValue("custom_stream_6").customCardCount)
        job.cancel()
    }

    @Test
    fun `where a category's cards would move to follows its kind`() = runTest(dispatcher) {
        val vm = viewModel()
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val rows = vm.uiState.value.categories.associateBy { it.id }
        assertEquals("Game", rows.getValue("custom_ff_5").cardHomeName)
        assertEquals("App Store", rows.getValue("custom_stream_6").cardHomeName)
        job.cancel()
    }

    @Test
    fun `delete passes the chosen answer for the custom cards through`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.delete("custom_ff_5", CollectionsOnDelete.DELETE)
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.delete("custom_ff_5", CollectionsOnDelete.DELETE) }
    }

    @Test
    fun `deleting the open category returns to the list`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.openDetail("custom_ff_5")

        vm.delete("custom_ff_5", CollectionsOnDelete.MOVE)
        advanceUntilIdle()

        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(CategoryStep.LIST, vm.uiState.value.step)
        job.cancel()
    }
}
