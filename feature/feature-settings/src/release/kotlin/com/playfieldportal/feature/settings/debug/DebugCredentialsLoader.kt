package com.playfieldportal.feature.settings.debug

import javax.inject.Inject

/**
 * Release stand-in for the debug-only credentials loader (src/debug). It exists so the shared
 * ViewModel compiles and injects in both variants; it reads and saves nothing, and the Settings row
 * that would call it is compiled out of release builds.
 */
class DebugCredentialsLoader @Inject constructor() {
    suspend fun load(text: String): DebugCredentialsResult =
        DebugCredentialsResult("Credentials files can only be loaded in debug builds", anyUnprotected = false)
}
