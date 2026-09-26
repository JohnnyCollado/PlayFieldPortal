package com.playfieldportal.feature.achievements.provider.localsteam

import com.playfieldportal.core.domain.model.GamepadAction
import io.mockk.coEvery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import io.mockk.mockk
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The convert picker's state machine: rows, focus, selection and the tally.
 *
 * The focus index lives here rather than in each hosting ViewModel so that the XMB card and the
 * Library Manager cannot drift apart on a controller — which is also why controller input is tested
 * at this level rather than through either screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalSteamConvertPickerControllerTest {

    private val generator = mockk<LocalSteamSchemaGenerator>()

    private fun game(name: String, appId: String) =
        LocalSteamGame(folderName = name, folderDocId = "doc-$appId", appId = appId, achievementsUri = null)

    /** Every probe answers with a count, so rows are selectable and the panel stops loading. */
    private fun probesAnswer(count: Int = 12) {
        coEvery { generator.probe(any()) } returns LocalSteamSchemaGenerator.Probe.Count(count)
    }

    @Test
    fun `empty list completes immediately with a zero outcome and no picker`() = runTest {
        val controller = LocalSteamConvertPickerController(generator, this)
        var outcome: LocalSteamConvertPickerController.Outcome? = null

        controller.start(emptyList()) { outcome = it }

        assertNull(controller.picker.value)
        assertEquals(LocalSteamConvertPickerController.Outcome(0, 0, 0, 0, 0), outcome)
    }

    @Test
    fun `start opens the picker with every row pre-checked`() = runTest {
        probesAnswer()
        val controller = LocalSteamConvertPickerController(generator, this)

        controller.start(listOf(game("A", "1"), game("B", "2"))) {}

        val picker = controller.picker.value!!
        assertEquals(2, picker.rows.size)
        assertTrue(picker.rows.all { it.selected })
        assertEquals(2, picker.selectedCount)
        assertEquals(0, picker.focus)
    }

    @Test
    fun `the probe fills each row's coin count and stops the loading state`() = runTest {
        coEvery { generator.probe("1") } returns LocalSteamSchemaGenerator.Probe.Count(51)
        coEvery { generator.probe("2") } returns LocalSteamSchemaGenerator.Probe.Count(7)
        val controller = LocalSteamConvertPickerController(generator, this)

        controller.start(listOf(game("A", "1"), game("B", "2"))) {}
        advanceUntilIdle()

        val picker = controller.picker.value!!
        assertFalse(picker.loading)
        assertEquals(listOf(51, 7), picker.rows.map { it.achievementCount })
        assertTrue(picker.rows[0].note.contains("51 coins"))
    }

    @Test
    fun `a game Steam keeps no list for is unselectable up front, never converted then failed`() = runTest {
        coEvery { generator.probe("1") } returns LocalSteamSchemaGenerator.Probe.Count(12)
        coEvery { generator.probe("2") } returns LocalSteamSchemaGenerator.Probe.NoAchievements
        coEvery { generator.generate(any()) } returns LocalSteamSchemaGenerator.Result.Written
        val controller = LocalSteamConvertPickerController(generator, this)
        var outcome: LocalSteamConvertPickerController.Outcome? = null

        controller.start(listOf(game("A", "1"), game("B", "2"))) { outcome = it }
        advanceUntilIdle()

        val picker = controller.picker.value!!
        assertTrue(picker.rows[1].unselectable)
        assertFalse(picker.rows[1].selected)
        assertTrue(picker.rows[1].note.contains("No list on Steam"))
        assertEquals(1, picker.selectedCount)

        // And it stays out of the run even if something tries to check it.
        controller.toggle(1)
        assertFalse(controller.picker.value!!.rows[1].selected)
        controller.confirm()
        advanceUntilIdle()
        val reported = outcome!!
        assertEquals(1, reported.converted)
        assertEquals(1, reported.skipped)
    }

    @Test
    fun `a missing API key marks every row unselectable and disables confirm`() = runTest {
        coEvery { generator.probe(any()) } returns LocalSteamSchemaGenerator.Probe.NoKey
        val controller = LocalSteamConvertPickerController(generator, this)

        controller.start(listOf(game("A", "1"))) {}
        advanceUntilIdle()

        val picker = controller.picker.value!!
        assertTrue(picker.rows.all { it.unselectable })
        assertFalse(picker.canConfirm)
        assertTrue(picker.rows[0].note.contains("Steam Web API key"))
    }

    @Test
    fun `a store that did not answer leaves the row selectable — trying again is reasonable`() = runTest {
        coEvery { generator.probe(any()) } returns LocalSteamSchemaGenerator.Probe.Unavailable
        val controller = LocalSteamConvertPickerController(generator, this)

        controller.start(listOf(game("A", "1"))) {}
        advanceUntilIdle()

        val picker = controller.picker.value!!
        assertFalse(picker.rows[0].unselectable)
        assertTrue(picker.canConfirm)
    }

    @Test
    fun `focus moves with Up and Down and clamps at both ends`() = runTest {
        probesAnswer()
        val controller = LocalSteamConvertPickerController(generator, this)
        controller.start(listOf(game("A", "1"), game("B", "2"), game("C", "3"))) {}

        controller.onGamepadAction(GamepadAction.NAVIGATE_UP)
        assertEquals(0, controller.picker.value!!.focus, "held Up at the top must not wrap")

        controller.onGamepadAction(GamepadAction.NAVIGATE_DOWN)
        controller.onGamepadAction(GamepadAction.NAVIGATE_DOWN)
        controller.onGamepadAction(GamepadAction.NAVIGATE_DOWN)
        assertEquals(2, controller.picker.value!!.focus, "held Down at the bottom must not wrap")
    }

    @Test
    fun `Select toggles the focused row and Triangle is all-or-none`() = runTest {
        probesAnswer()
        val controller = LocalSteamConvertPickerController(generator, this)
        controller.start(listOf(game("A", "1"), game("B", "2"))) {}

        controller.onGamepadAction(GamepadAction.NAVIGATE_DOWN)
        controller.onGamepadAction(GamepadAction.SELECT)
        assertEquals(listOf(true, false), controller.picker.value!!.rows.map { it.selected })

        // One row is unchecked, so Triangle means "all".
        controller.onGamepadAction(GamepadAction.OPEN_CONTEXT_MENU)
        assertTrue(controller.picker.value!!.rows.all { it.selected })

        // Everything is checked, so the next Triangle means "none".
        controller.onGamepadAction(GamepadAction.OPEN_CONTEXT_MENU)
        assertTrue(controller.picker.value!!.rows.none { it.selected })
    }

    @Test
    fun `Start confirms and Back cancels`() = runTest {
        probesAnswer()
        coEvery { generator.generate(any()) } returns LocalSteamSchemaGenerator.Result.Written
        val controller = LocalSteamConvertPickerController(generator, this)
        var outcome: LocalSteamConvertPickerController.Outcome? = null

        controller.start(listOf(game("A", "1"))) { outcome = it }
        advanceUntilIdle()
        assertTrue(controller.onGamepadAction(GamepadAction.HOME))
        advanceUntilIdle()
        assertEquals(1, outcome!!.converted)

        var second: LocalSteamConvertPickerController.Outcome? = null
        controller.start(listOf(game("B", "2"))) { second = it }
        advanceUntilIdle()
        assertTrue(controller.onGamepadAction(GamepadAction.BACK))
        assertNull(controller.picker.value)
        assertEquals(1, second!!.skipped)
    }

    @Test
    fun `confirm converts every checked game, reports the tally, and names what it wrote into`() = runTest {
        probesAnswer()
        coEvery { generator.generate(any()) } returns LocalSteamSchemaGenerator.Result.Written
        val controller = LocalSteamConvertPickerController(generator, this)
        var outcome: LocalSteamConvertPickerController.Outcome? = null
        val folders = listOf(game("A", "1"), game("B", "2"))

        controller.start(folders) { outcome = it }
        advanceUntilIdle()
        controller.confirm()
        advanceUntilIdle()

        assertNull(controller.picker.value)
        val reported = outcome!!
        assertEquals(2, reported.converted)
        assertEquals(0, reported.skipped)
        // The caller links and syncs exactly these, so they have to come back out.
        assertEquals(folders, reported.convertedFolders)
    }

    @Test
    fun `unchecked rows are skipped, not converted`() = runTest {
        probesAnswer()
        coEvery { generator.generate(any()) } returns LocalSteamSchemaGenerator.Result.Written
        val controller = LocalSteamConvertPickerController(generator, this)
        var outcome: LocalSteamConvertPickerController.Outcome? = null

        controller.start(listOf(game("A", "1"), game("B", "2"))) { outcome = it }
        advanceUntilIdle()
        controller.toggle(1) // uncheck B
        controller.confirm()
        advanceUntilIdle()

        val reported = outcome!!
        assertEquals(1, reported.converted)
        assertEquals(1, reported.skipped)
        assertEquals(listOf("A"), reported.convertedFolders.map { it.folderName })
    }

    @Test
    fun `cancel converts nothing and reports every game skipped`() = runTest {
        probesAnswer()
        val controller = LocalSteamConvertPickerController(generator, this)
        var outcome: LocalSteamConvertPickerController.Outcome? = null

        controller.start(listOf(game("A", "1"), game("B", "2"))) { outcome = it }
        controller.cancel()

        assertNull(controller.picker.value)
        assertEquals(LocalSteamConvertPickerController.Outcome(0, 0, 0, 0, 2), outcome)
    }

    @Test
    fun `Skip and Sync writes into no game folder at all`() = runTest {
        probesAnswer()
        val controller = LocalSteamConvertPickerController(generator, this)
        var outcome: LocalSteamConvertPickerController.Outcome? = null

        controller.start(listOf(game("A", "1"))) { outcome = it }
        advanceUntilIdle()
        controller.skip()

        assertNull(controller.picker.value)
        val reported = outcome!!
        assertEquals(0, reported.converted)
        assertTrue(reported.convertedFolders.isEmpty())
    }

    @Test
    fun `mixed results are tallied per category`() = runTest {
        probesAnswer()
        coEvery { generator.generate(match { it.appId == "1" }) } returns LocalSteamSchemaGenerator.Result.Written
        coEvery { generator.generate(match { it.appId == "2" }) } returns LocalSteamSchemaGenerator.Result.NoAchievements
        coEvery { generator.generate(match { it.appId == "3" }) } returns LocalSteamSchemaGenerator.Result.NoKey
        val controller = LocalSteamConvertPickerController(generator, this)
        var outcome: LocalSteamConvertPickerController.Outcome? = null

        controller.start(listOf(game("A", "1"), game("B", "2"), game("C", "3"))) { outcome = it }
        advanceUntilIdle()
        controller.confirm()
        advanceUntilIdle()

        assertEquals(
            LocalSteamConvertPickerController.Outcome(
                converted = 1, noAchievements = 1, noKey = 1, failed = 0, skipped = 0,
                convertedFolders = listOf(game("A", "1")),
            ),
            outcome,
        )
    }
}
