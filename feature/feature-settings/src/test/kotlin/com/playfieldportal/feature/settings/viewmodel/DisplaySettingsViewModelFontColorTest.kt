package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.data.repository.GameBootPreferences
import com.playfieldportal.core.data.repository.UiMediaStore
import com.playfieldportal.core.domain.model.TextLegibilityStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The font-colour pref contract. Skeleton copied from `DisplaySettingsViewModelLegibilityTest`.
 *
 * The interesting cases are the two opt-outs, because they are easy to conflate and mean
 * different things: `exact` stops the *adjustment*, `suppressed` stops only the *notice*.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DisplaySettingsViewModelFontColorTest {

    private val dispatcher = StandardTestDispatcher()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var vm: DisplaySettingsViewModel

    /** A saturated mid-tone that cannot clear 4.5:1 on the settings backdrop as picked. */
    private val failingColor = 0xFF4A90D9L

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        runBlocking { context.pfpDataStore.edit { it.clear() } }
        vm = DisplaySettingsViewModel(
            context,
            UiMediaStore(context),
            GameBootPreferences(context),
            io.mockk.mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `picking persists the long and clearing removes the key`() = runTest(dispatcher) {
        assertNull(vm.uiState.first().textColorArgb)

        vm.setTextColor(failingColor)
        eventually("colour persisted") {
            context.pfpDataStore.data.first()[KEY_TEXT_COLOR] == failingColor
        }
        eventually("colour surfaced") { vm.uiState.first().textColorArgb == failingColor }

        vm.setTextColor(null)
        eventually("key removed, not zeroed") {
            !context.pfpDataStore.data.first().contains(KEY_TEXT_COLOR)
        }
        eventually("cleared colour surfaced") { vm.uiState.first().textColorArgb == null }
    }

    @Test
    fun `a colour that cannot pass raises the notice`() = runTest(dispatcher) {
        vm.setTextColor(failingColor)
        eventually("notice raised") { vm.uiState.first().textContrastNotice != null }
        assertNotNull(vm.uiState.first().textContrastNotice)

        vm.dismissTextContrastNotice()
        eventually("notice dismissed") { vm.uiState.first().textContrastNotice == null }
        // Transient: dismissing must not write anything.
        assertEquals(false, context.pfpDataStore.data.first()[KEY_NOTICE_SUPPRESSED] ?: false)
    }

    @Test
    fun `white raises no notice at all`() = runTest(dispatcher) {
        vm.setTextColor(0xFFFFFFFFL)
        eventually("colour surfaced") { vm.uiState.first().textColorArgb == 0xFFFFFFFFL }
        assertNull("white already passes on the solved scrim", vm.uiState.first().textContrastNotice)
    }

    @Test
    fun `exact suppresses the adjustment notice and persists`() = runTest(dispatcher) {
        vm.setTextColorExact(true)
        eventually("exact persisted") {
            context.pfpDataStore.data.first()[KEY_EXACT] == true
        }
        eventually("exact surfaced") { vm.uiState.first().textColorExact }

        vm.setTextColor(failingColor)
        eventually("colour surfaced") { vm.uiState.first().textColorArgb == failingColor }
        assertNull(
            "exact means the colour is rendered as picked, so there is no adjustment to report",
            vm.uiState.first().textContrastNotice,
        )
    }

    @Test
    fun `suppressed stops the notice while adjustment continues`() = runTest(dispatcher) {
        vm.suppressTextContrastNotice()
        eventually("suppression persisted") {
            context.pfpDataStore.data.first()[KEY_NOTICE_SUPPRESSED] == true
        }

        vm.setTextColor(failingColor)
        eventually("colour surfaced") { vm.uiState.first().textColorArgb == failingColor }
        assertNull(vm.uiState.first().textContrastNotice)
        // The distinction that matters: the clamp is NOT switched off, only the telling.
        assertEquals(false, vm.uiState.first().textColorExact)
    }

    @Test
    fun `an unknown persisted legibility style surfaces as the default`() = runTest(dispatcher) {
        context.pfpDataStore.edit { it[KEY_LEGIBILITY] = "PLATE_HEAVY" }

        eventually("stale value tolerated") {
            vm.uiState.first().textLegibility == TextLegibilityStyle.DEFAULT
        }

        vm.cycleTextLegibility()
        eventually("cycle works after a stale value") {
            vm.uiState.first().textLegibility != TextLegibilityStyle.DEFAULT
        }
    }

    @Test
    fun `cycling legibility persists the enum name`() = runTest(dispatcher) {
        val expected = TextLegibilityStyle.entries
        val start = expected.indexOf(TextLegibilityStyle.DEFAULT)

        vm.cycleTextLegibility()
        eventually("next style persisted") {
            context.pfpDataStore.data.first()[KEY_LEGIBILITY] ==
                expected[(start + 1) % expected.size].name
        }
    }

    /** Same wait idiom as the sibling legibility test — see its KDoc. */
    private suspend fun TestScope.eventually(reason: String, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("condition not met within 10s: $reason")
            }
            advanceUntilIdle()
            withContext(Dispatchers.IO) { Thread.sleep(25) }
        }
        advanceUntilIdle()
    }

    private companion object {
        // Mirrored by their string contract, like the sibling tests — these keys are private to
        // the ViewModel, and the string is the part that must not drift.
        val KEY_TEXT_COLOR = longPreferencesKey("display_text_color")
        val KEY_EXACT = booleanPreferencesKey("display_text_color_exact")
        val KEY_LEGIBILITY = stringPreferencesKey("display_text_legibility")
        val KEY_NOTICE_SUPPRESSED = booleanPreferencesKey("display_text_contrast_notice_suppressed")
    }
}
