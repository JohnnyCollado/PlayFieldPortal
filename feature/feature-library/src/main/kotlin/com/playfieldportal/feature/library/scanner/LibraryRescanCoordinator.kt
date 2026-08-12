package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.data.repository.LibraryReconciler
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.domain.repository.GameRepository
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class LibraryRescanCoordinator @Inject constructor(
    private val gameRepo: GameRepository,
    private val memoryCardRepo: MemoryCardRepository,
    private val romScanner: RomScanner,
    private val libraryReconciler: LibraryReconciler,
) {

    suspend fun onResume() {
        Timber.i("Library Rescan - onResume")
    }

    suspend fun onMediaMounted() {
        Timber.i("Library Rescan - onMediaMounted")
    }

}
