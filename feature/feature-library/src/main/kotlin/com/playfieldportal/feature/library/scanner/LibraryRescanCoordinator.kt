package com.playfieldportal.feature.library.scanner

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

@Singleton
class LibraryRescanCoordinator @Inject constructor(
    libraryScanner: LibraryScanner,
    @RescanApplicationScope scope: CoroutineScope,
) {
    private val bus = RescanTriggerBus(libraryScanner, scope)

    fun onResume() = bus.submit(RescanTrigger.AppResumed)
    fun onMediaMounted() = bus.submit(RescanTrigger.MediaMounted)
}
