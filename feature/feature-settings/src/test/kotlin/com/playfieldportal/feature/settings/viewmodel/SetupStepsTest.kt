package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.feature.settings.viewmodel.SetupStep.ACHIEVEMENTS
import com.playfieldportal.feature.settings.viewmodel.SetupStep.ARTWORK
import com.playfieldportal.feature.settings.viewmodel.SetupStep.CONTROLLER
import com.playfieldportal.feature.settings.viewmodel.SetupStep.EMULATORS
import com.playfieldportal.feature.settings.viewmodel.SetupStep.FINISH
import com.playfieldportal.feature.settings.viewmodel.SetupStep.HINTS
import com.playfieldportal.feature.settings.viewmodel.SetupStep.HOME_APP
import com.playfieldportal.feature.settings.viewmodel.SetupStep.MUSIC
import com.playfieldportal.feature.settings.viewmodel.SetupStep.PHOTO
import com.playfieldportal.feature.settings.viewmodel.SetupStep.RETROARCH
import com.playfieldportal.feature.settings.viewmodel.SetupStep.ROM_ROOTS
import com.playfieldportal.feature.settings.viewmodel.SetupStep.SERVICES
import com.playfieldportal.feature.settings.viewmodel.SetupStep.TROPHIES
import com.playfieldportal.feature.settings.viewmodel.SetupStep.VIDEO
import com.playfieldportal.feature.settings.viewmodel.SetupStep.WELCOME
import com.playfieldportal.feature.settings.viewmodel.SetupStep.WINDOWS
import org.junit.Assert.assertEquals
import org.junit.Test

/** Initial Setup page order and visibility (setup-wizard plan section 3). */
class SetupStepsTest {

    // The pages every run shows, in order: Welcome, Controller, the folder and service pages,
    // Hints & Touch and Finish.
    private val always = listOf(WELCOME, CONTROLLER, ROM_ROOTS, MUSIC, VIDEO, PHOTO, ARTWORK, SERVICES, ACHIEVEMENTS)

    @Test fun `nothing installed and not Home shows the always pages plus Home App`() {
        assertEquals(always + listOf(HINTS, HOME_APP, FINISH), setupSteps(SetupAvailability()))
    }

    @Test fun `Vita3K alone brings the Trophies page`() {
        assertEquals(
            always + listOf(TROPHIES, HINTS, HOME_APP, FINISH),
            setupSteps(SetupAvailability(vita3K = true)),
        )
    }

    @Test fun `ARMSX3 alone brings the Trophies page`() {
        assertEquals(
            always + listOf(TROPHIES, HINTS, HOME_APP, FINISH),
            setupSteps(SetupAvailability(armsx3 = true)),
        )
    }

    @Test fun `Vita3K and ARMSX3 together share one Trophies page`() {
        assertEquals(
            always + listOf(TROPHIES, HINTS, HOME_APP, FINISH),
            setupSteps(SetupAvailability(vita3K = true, armsx3 = true)),
        )
    }

    @Test fun `a verified PC launcher brings the Windows Games page`() {
        assertEquals(
            always + listOf(WINDOWS, HINTS, HOME_APP, FINISH),
            setupSteps(SetupAvailability(pcLauncher = true)),
        )
    }

    @Test fun `an installed known emulator brings the Emulators page`() {
        assertEquals(
            always + listOf(EMULATORS, HINTS, HOME_APP, FINISH),
            setupSteps(SetupAvailability(knownEmulator = true)),
        )
    }

    @Test fun `already Home skips the Home App page`() {
        assertEquals(always + listOf(HINTS, FINISH), setupSteps(SetupAvailability(alreadyHome = true)))
    }

    @Test fun `everything installed shows every page in plan order`() {
        assertEquals(
            always + listOf(TROPHIES, RETROARCH, EMULATORS, WINDOWS, HINTS, HOME_APP, FINISH),
            setupSteps(
                SetupAvailability(
                    retroArch = true, vita3K = true, armsx3 = true,
                    knownEmulator = true, pcLauncher = true, alreadyHome = false,
                ),
            ),
        )
    }

    @Test fun `the full sequence numbers Finish as step 16`() {
        val all = SetupAvailability(
            retroArch = true, vita3K = true, armsx3 = true, knownEmulator = true, pcLauncher = true,
        )
        assertEquals(16, setupSteps(all).indexOf(FINISH) + 1)
    }

    @Test fun `Controller is the second page so later pages show the right glyphs`() {
        assertEquals(CONTROLLER, setupSteps(SetupAvailability())[1])
    }
}
