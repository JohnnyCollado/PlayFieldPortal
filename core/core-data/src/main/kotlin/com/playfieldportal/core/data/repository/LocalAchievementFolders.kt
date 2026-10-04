package com.playfieldportal.core.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The emulator data folders that hold locally saved trophies and achievements — Vita3K, ARMSX3,
 * X360 Mobile and XenDroid — seen together. Linking any one of them is a connected achievement
 * source, just like saving RetroAchievements or Steam credentials.
 */
@Singleton
class LocalAchievementFolders @Inject constructor(
    vita3KLibrary: Vita3KLibrary,
    ps3DataLibrary: Ps3DataLibrary,
    xbox360DataLibrary: Xbox360DataLibrary,
) {
    /** True while at least one emulator data folder is linked. */
    val anyLinked: Flow<Boolean> = combine(
        vita3KLibrary.ux0TreeUriFlow,
        ps3DataLibrary.dataTreeUriFlow,
        xbox360DataLibrary.treeUriFlow(Xbox360Emulator.X360_MOBILE),
        xbox360DataLibrary.treeUriFlow(Xbox360Emulator.XENDROID),
    ) { vita, ps3, x360Mobile, xenDroid ->
        listOf(vita, ps3, x360Mobile, xenDroid).any { !it.isNullOrBlank() }
    }.distinctUntilChanged()
}
