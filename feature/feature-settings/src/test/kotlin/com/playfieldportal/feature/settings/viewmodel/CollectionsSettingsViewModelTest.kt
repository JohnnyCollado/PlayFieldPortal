package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.data.repository.CategoryRepositoryImpl
import com.playfieldportal.core.data.repository.CollectionRepository
import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Settings ▸ Custom Memory Cards can create a card in any category that shows cards.
 *
 * Survey L28 (docs/audits/codebase-survey-2026-10-22.md): the "Add to Category" picker was fed
 * `filter { it.isGamingCategory }`, so a non-gaming card could never be created from Settings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CollectionsSettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun cat(id: String, gaming: Boolean = false) =
        Category(id, id, "ic", type = CategoryType.BUILT_IN, position = 0, isGamingCategory = gaming)

    @Test
    fun `the picker offers app categories as well as gaming ones`() = runTest(dispatcher) {
        val categories = mockk<CategoryRepositoryImpl> {
            every { observeAll() } returns flowOf(
                listOf(cat(BuiltInCategory.GAMES, gaming = true), cat("network"), cat(BuiltInCategory.MUSIC), cat("app_store")),
            )
        }
        val collections = mockk<CollectionRepository> { every { observeCollections() } returns emptyFlow() }
        val vm = CollectionsSettingsViewModel(
            collections, categories,
            mockk(relaxed = true) { every { observeStoredKeys() } returns flowOf(emptySet()) },
            mockk(relaxed = true),
        )

        val offered = vm.cardCategories.first { it.isNotEmpty() }.map { it.id }

        assertEquals(listOf(BuiltInCategory.GAMES, "network", "app_store"), offered)
    }
}
