package com.playfieldportal.feature.achievements.provider.localsteam

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalSteamConvertPickerControllerTest {

    private val generator = mockk<LocalSteamSchemaGenerator>()

    private fun game(name: String, appId: String) =
        LocalSteamGame(folderName = name, folderDocId = "doc-$appId", appId = appId, achievementsUri = null)

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
        val controller = LocalSteamConvertPickerController(generator, this)

        controller.start(listOf(game("A", "1"), game("B", "2"))) {}

        val picker = controller.picker.value!!
        assertEquals(2, picker.rows.size)
        assertTrue(picker.rows.all { it.selected })
        assertEquals(2, picker.selectedCount)
    }

    @Test
    fun `confirm converts every checked game and reports the tally`() = runTest {
        coEvery { generator.generate(any()) } returns LocalSteamSchemaGenerator.Result.Written
        val controller = LocalSteamConvertPickerController(generator, this)
        var outcome: LocalSteamConvertPickerController.Outcome? = null

        controller.start(listOf(game("A", "1"), game("B", "2"))) { outcome = it }
        controller.confirm()
        advanceUntilIdle()

        assertNull(controller.picker.value)
        assertEquals(LocalSteamConvertPickerController.Outcome(converted = 2, noAchievements = 0, noKey = 0, failed = 0, skipped = 0), outcome)
    }

    @Test
    fun `unchecked rows are skipped, not converted`() = runTest {
        coEvery { generator.generate(any()) } returns LocalSteamSchemaGenerator.Result.Written
        val controller = LocalSteamConvertPickerController(generator, this)
        var outcome: LocalSteamConvertPickerController.Outcome? = null

        controller.start(listOf(game("A", "1"), game("B", "2"))) { outcome = it }
        controller.toggle(1) // uncheck B
        controller.confirm()
        advanceUntilIdle()

        assertEquals(LocalSteamConvertPickerController.Outcome(converted = 1, noAchievements = 0, noKey = 0, failed = 0, skipped = 1), outcome)
    }

    @Test
    fun `cancel converts nothing and reports every game skipped`() = runTest {
        val controller = LocalSteamConvertPickerController(generator, this)
        var outcome: LocalSteamConvertPickerController.Outcome? = null

        controller.start(listOf(game("A", "1"), game("B", "2"))) { outcome = it }
        controller.cancel()

        assertNull(controller.picker.value)
        assertEquals(LocalSteamConvertPickerController.Outcome(0, 0, 0, 0, 2), outcome)
    }

    @Test
    fun `mixed results are tallied per category`() = runTest {
        coEvery { generator.generate(match { it.appId == "1" }) } returns LocalSteamSchemaGenerator.Result.Written
        coEvery { generator.generate(match { it.appId == "2" }) } returns LocalSteamSchemaGenerator.Result.NoAchievements
        coEvery { generator.generate(match { it.appId == "3" }) } returns LocalSteamSchemaGenerator.Result.NoKey
        val controller = LocalSteamConvertPickerController(generator, this)
        var outcome: LocalSteamConvertPickerController.Outcome? = null

        controller.start(listOf(game("A", "1"), game("B", "2"), game("C", "3"))) { outcome = it }
        controller.confirm()
        advanceUntilIdle()

        assertEquals(LocalSteamConvertPickerController.Outcome(converted = 1, noAchievements = 1, noKey = 1, failed = 0, skipped = 0), outcome)
    }
}
