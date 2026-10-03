package com.playfieldportal.feature.settings.viewmodel

/**
 * The pages of the first-run wizard, in order (setup-wizard plan section 3). Trophies, RetroArch,
 * Emulators and Windows Games appear only when their app is installed; Home App only while PFP
 * is not already the Home app. See [setupSteps].
 */
enum class SetupStep {
    WELCOME, CONTROLLER, ROM_ROOTS, MUSIC, VIDEO, PHOTO, ARTWORK, SERVICES, ACHIEVEMENTS,
    TROPHIES, RETROARCH, EMULATORS, WINDOWS, HINTS, HOME_APP, FINISH,
}

/** What is installed and set on the device when the wizard opens — decides the optional pages. */
data class SetupAvailability(
    val retroArch: Boolean = false,
    val vita3K: Boolean = false,
    val armsx3: Boolean = false,
    /** A standalone emulator PFP knows how to launch (RetroArch cores alone don't count). */
    val knownEmulator: Boolean = false,
    /** A fingerprint-verified PC launcher. */
    val pcLauncher: Boolean = false,
    val alreadyHome: Boolean = false,
)

/** The pages one run of the wizard shows, in order. */
fun setupSteps(availability: SetupAvailability): List<SetupStep> =
    SetupStep.entries.filter { step ->
        when (step) {
            SetupStep.TROPHIES  -> availability.vita3K || availability.armsx3
            SetupStep.RETROARCH -> availability.retroArch
            SetupStep.EMULATORS -> availability.knownEmulator
            SetupStep.WINDOWS   -> availability.pcLauncher
            SetupStep.HOME_APP  -> !availability.alreadyHome
            else                -> true
        }
    }
