package com.playfieldportal.feature.library.scanner

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

@Singleton
class LibraryRescanCoordinator @Inject constructor(
    libraryScanner: LibraryScanner,
    romRootDiscoveryScanner: RomRootDiscoveryScanner,
    @RescanApplicationScope scope: CoroutineScope,
) {
    private val bus = RescanTriggerBus(libraryScanner, romRootDiscoveryScanner, scope)

    fun onResume() = bus.submit(RescanTrigger.AppResumed)
    fun onMediaMounted() = bus.submit(RescanTrigger.MediaMounted)
}
