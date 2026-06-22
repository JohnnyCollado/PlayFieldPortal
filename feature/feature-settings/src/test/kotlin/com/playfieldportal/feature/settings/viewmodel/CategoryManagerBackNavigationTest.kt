package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.data.repository.CategoryRepositoryImpl
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Regression coverage for controller Back / nested navigation: pressing Back inside a
// submenu must collapse one level (consumed) rather than jumping straight to the XMB.
// The controller Back path now routes through this same onBack() handler the on-screen
// Back button uses, so this guards the one-level-up contract for both.
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryManagerBackNavigationTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): CategoryManagerViewModel {
        val repo = mockk<CategoryRepositoryImpl>()
        every { repo.observeAll() } returns flowOf(emptyList())
        every { repo.isProtected(any()) } returns false
        return CategoryManagerViewModel(repo)
    }

    @Test
    fun back_at_top_level_is_not_consumed() {
        val vm = viewModel()
        // At the LIST level Back is not consumed, so the caller closes the overlay → XMB.
        assertFalse(vm.onBack())
    }

    @Test
    fun back_in_submenu_collapses_one_level_then_reaches_top() {
        val vm = viewModel()
        vm.openDetail("videos")        // enter a submenu (e.g. a category's detail)
        assertTrue(vm.onBack())        // first Back → collapses submenu (consumed)
        assertFalse(vm.onBack())       // now at top → not consumed (caller returns to XMB)
    }

    @Test
    fun back_from_icon_picker_returns_to_list() {
        val vm = viewModel()
        vm.startCreate()
        vm.confirmCreateName("Streaming")   // advances to the icon-picker step
        assertTrue(vm.onBack())             // Back collapses the picker (consumed)
        assertFalse(vm.onBack())            // back at the list/top level
    }
}
