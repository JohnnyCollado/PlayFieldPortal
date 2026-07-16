package com.playfieldportal.feature.achievements.provider.localsteam

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalSteamSchemaPromptControllerTest {

    private val generator = mockk<LocalSteamSchemaGenerator>()

    private fun gameNamed(name: String, appId: String) =
        LocalSteamGame(name, "doc:$name", appId, null, "tree", "settings:$name", hasSchema = false)

    @Test
    fun `empty list completes immediately with a zero outcome and no prompt`() = runTest {
        val controller = LocalSteamSchemaPromptController(generator, this)
        var outcome: LocalSteamSchemaPromptController.Outcome? = null
        controller.start(emptyList()) { outcome = it }
        assertNull(controller.prompt.value)
        assertEquals(LocalSteamSchemaPromptController.Outcome(0, 0, 0), outcome)
    }

    @Test
    fun `no skips a game without generating it`() = runTest {
        val controller = LocalSteamSchemaPromptController(generator, this)
        var outcome: LocalSteamSchemaPromptController.Outcome? = null
        controller.start(listOf(gameNamed("A", "1"))) { outcome = it }

        assertEquals("A", controller.prompt.value?.folderName)
        controller.no()
        advanceUntilIdle()

        assertNull(controller.prompt.value)
        assertEquals(LocalSteamSchemaPromptController.Outcome(0, 0, 1), outcome)
        coVerify(exactly = 0) { generator.generate(any()) }
    }

    @Test
    fun `yes generates the current game then advances to the next`() = runTest {
        coEvery { generator.generate(any()) } returns LocalSteamSchemaGenerator.Result.Written
        val controller = LocalSteamSchemaPromptController(generator, this)
        var outcome: LocalSteamSchemaPromptController.Outcome? = null
        controller.start(listOf(gameNamed("A", "1"), gameNamed("B", "2"))) { outcome = it }

        assertEquals(1, controller.prompt.value?.index)
        assertEquals(2, controller.prompt.value?.total)
        controller.yes()
        advanceUntilIdle()

        // Advanced to the second game, first one generated.
        assertEquals("B", controller.prompt.value?.folderName)
        controller.yes()
        advanceUntilIdle()

        assertNull(controller.prompt.value)
        assertEquals(LocalSteamSchemaPromptController.Outcome(2, 0, 0), outcome)
        coVerify(exactly = 2) { generator.generate(any()) }
    }

    @Test
    fun `yes to all generates every remaining game with no further prompts`() = runTest {
        coEvery { generator.generate(any()) } returns LocalSteamSchemaGenerator.Result.Written
        val controller = LocalSteamSchemaPromptController(generator, this)
        var outcome: LocalSteamSchemaPromptController.Outcome? = null
        controller.start(listOf(gameNamed("A", "1"), gameNamed("B", "2"), gameNamed("C", "3"))) { outcome = it }

        controller.no() // skip A
        advanceUntilIdle()
        assertEquals("B", controller.prompt.value?.folderName)

        controller.yesToAll() // B and C
        advanceUntilIdle()

        assertNull(controller.prompt.value)
        assertEquals(LocalSteamSchemaPromptController.Outcome(2, 0, 1), outcome)
        coVerify(exactly = 2) { generator.generate(any()) }
    }

    @Test
    fun `a failed generate counts as failed, not generated`() = runTest {
        coEvery { generator.generate(any()) } returns LocalSteamSchemaGenerator.Result.NoKey
        val controller = LocalSteamSchemaPromptController(generator, this)
        var outcome: LocalSteamSchemaPromptController.Outcome? = null
        controller.start(listOf(gameNamed("A", "1"))) { outcome = it }
        controller.yes()
        advanceUntilIdle()

        assertEquals(LocalSteamSchemaPromptController.Outcome(0, 1, 0), outcome)
    }
}
