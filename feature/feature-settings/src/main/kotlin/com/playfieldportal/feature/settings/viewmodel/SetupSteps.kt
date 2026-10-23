package com.playfieldportal.feature.settings.viewmodel

/**
 * The pages of the first-run wizard, in order (setup-wizard plan section 3). Local Achievements, RetroArch,
 * Emulators and Windows Games appear only when their app is installed; Home App only while PFP
 * is not already the Home app. See [setupSteps].
 */
enum class SetupStep {
    WELCOME, CONTROLLER, ROM_ROOTS, MUSIC, VIDEO, PHOTO, ARTWORK, SERVICES, ACHIEVEMENTS,
    LOCAL_ACHIEVEMENTS, RETROARCH, EMULATORS, WINDOWS, HINTS, HOME_APP, FINISH,
}

/**
 * Whether [step]'s footer offers Skip. Welcome has its own "Skip Setup" row, and a footer Skip
 * there only did what Get Started does (survey U11); Finish has nowhere to skip to; Artwork is
 * required, so it has no Skip until its folder is ready (a re-run with a healthy folder may skip).
 */
fun wizardOffersSkip(step: SetupStep, artworkReady: Boolean): Boolean = when (step) {
    SetupStep.WELCOME, SetupStep.FINISH -> false
    SetupStep.ARTWORK -> artworkReady
    else -> true
}

/** What is installed and set on the device when the wizard opens — decides the optional pages. */
data class SetupAvailability(
    val retroArch: Boolean = false,
    val vita3K: Boolean = false,
    val armsx3: Boolean = false,
    val x360Mobile: Boolean = false,
    val xenDroid: Boolean = false,
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
            SetupStep.LOCAL_ACHIEVEMENTS ->
                availability.vita3K || availability.armsx3 || availability.x360Mobile || availability.xenDroid
            SetupStep.RETROARCH -> availability.retroArch
            SetupStep.EMULATORS -> availability.knownEmulator
            SetupStep.WINDOWS   -> availability.pcLauncher
            SetupStep.HOME_APP  -> !availability.alreadyHome
            else                -> true
        }
    }
