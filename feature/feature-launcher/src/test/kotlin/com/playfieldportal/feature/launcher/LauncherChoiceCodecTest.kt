package com.playfieldportal.feature.launcher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A choice is stored per package id + signer, so a reinstall signed by someone else asks again. */
class LauncherChoiceCodecTest {

    private val signerA = "a".repeat(64)
    private val signerB = "b".repeat(64)

    @Test
    fun `choices round-trip through the stored set`() {
        val choices = LauncherChoices(
            mapOf(
                LauncherChoiceKey("com.xiaoji.egggame", signerA) to LauncherChoice.BANNERHUB,
                LauncherChoiceKey("com.example.wine", null) to LauncherChoice.NOT_A_LAUNCHER,
            ),
        )

        assertEquals(choices, LauncherChoices.decode(choices.encode()))
    }

    @Test
    fun `a reinstall with a different signer has no choice`() {
        val choices = LauncherChoices(mapOf(LauncherChoiceKey("com.xiaoji.egggame", signerA) to LauncherChoice.GAMEHUB))

        assertEquals(LauncherChoice.GAMEHUB, choices.choiceFor("com.xiaoji.egggame", signerA))
        assertNull(choices.choiceFor("com.xiaoji.egggame", signerB))
    }

    @Test
    fun `signer case does not split a key`() {
        val choices = LauncherChoices(mapOf(LauncherChoiceKey("p", signerA.uppercase()) to LauncherChoice.WINLATOR))

        assertEquals(LauncherChoice.WINLATOR, choices.choiceFor("p", signerA))
    }

    @Test
    fun `corrupt or retired entries are skipped, never fatal`() {
        val decoded = LauncherChoices.decode(setOf("garbage", "p|s|NOT_A_REAL_CHOICE", "ok.pkg|$signerA|WINLATOR"))

        assertEquals(LauncherChoices(mapOf(LauncherChoiceKey("ok.pkg", signerA) to LauncherChoice.WINLATOR)), decoded)
    }

    @Test
    fun `setting a choice replaces the old one for that install`() {
        val updated = LauncherChoices.EMPTY
            .with("p", signerA, LauncherChoice.GAMEHUB)
            .with("p", signerA, LauncherChoice.GAMENATIVE)

        assertEquals(LauncherChoice.GAMENATIVE, updated.choiceFor("p", signerA))
        assertEquals(1, updated.byKey.size)
    }

    @Test
    fun `chosen launcher packages are listed for the catalog to verify`() {
        val choices = LauncherChoices.EMPTY
            .with("a.pkg", signerA, LauncherChoice.WINLATOR)
            .with("b.pkg", signerA, LauncherChoice.NOT_A_LAUNCHER)

        assertTrue("a.pkg" in choices.chosenLauncherPackages())
        assertTrue("b.pkg" !in choices.chosenLauncherPackages())
    }
}
