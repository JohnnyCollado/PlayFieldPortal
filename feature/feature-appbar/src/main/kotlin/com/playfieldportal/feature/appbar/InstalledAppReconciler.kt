package com.playfieldportal.feature.appbar

import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// The Android Memory Card's platform id (mirrors AppDrawerViewModel / XMBViewModel).
private const val ANDROID_PLATFORM_ID = "android"

/**
 * Keeps app-backed library rows in step with what is actually installed.
 *
 * An app the user marked as a game gets a row on the Android Memory Card. Uninstalling the app left
 * that row behind: the card kept a phantom entry with a live count that launched nothing, because
 * the games table is its own list and was never diffed against installed packages.
 *
 * The row is marked **missing, never deleted** — the same policy [LibraryReconciler] applies to a
 * ROM whose file has gone. Favorites, play stats, artwork and achievement links survive, the entry
 * disappears from the card (observeByPlatform and the count query both filter is_missing = 0), and
 * a reinstall brings it back whole. Real deletion stays the user's explicit action.
 *
 * Nothing prunes `category_items` or `app_overrides`, deliberately. Those rows are invisible while
 * the app is gone — every list that reads them joins against the installed set first — and keeping
 * them is what makes a reinstall restore the user's custom label and category placement. Absence is
 * a state here, not a deletion, and that has to hold for all of an app's state or none of it.
 */
@Singleton
class InstalledAppReconciler @Inject constructor(
    private val appCategoryRepository: AppCategoryRepository,
    private val gameRepository: GameRepository,
    private val memoryCardRepository: MemoryCardRepository,
    @AppCatalogScope private val scope: CoroutineScope,
) {
    /** [skipped] is true when the survey was untrustworthy and nothing was touched. */
    data class Result(val markedSeen: Int, val markedMissing: Int, val skipped: Boolean)

    /**
     * Subscribes to the app catalog. Reconciling on every catalog change rather than only at
     * startup is safe precisely because this marks rather than deletes: a package that vanishes
     * during an update is flagged and unflagged again when it returns, costing a flag write rather
     * than the user's data. The catalog's own settle delay usually collapses an update into a
     * single pass where the app is present, so even the flag rarely moves.
     *
     * changes() replays an opening value on subscribe, which is the startup pass.
     */
    fun start() {
        scope.launch {
            appCategoryRepository.changes().collect {
                runCatching { reconcile(appCategoryRepository.allInstalledApps().map { app -> app.packageName }.toSet()) }
                    .onFailure { Timber.w(it, "App presence reconcile failed") }
            }
        }
    }

    /**
     * Diffs the Android Memory Card's rows against [installedPackages] and moves only the rows
     * whose presence actually changed.
     *
     * Writing only the difference is what makes this safe to run on every catalog change: in the
     * steady state it issues no writes at all, so it cannot churn the games table or wake every
     * observer of it each time the user edits a category assignment.
     */
    suspend fun reconcile(
        installedPackages: Set<String>,
        now: Long = System.currentTimeMillis(),
    ): Result {
        val rows = gameRepository.getByPlatform(ANDROID_PLATFORM_ID)
            .filter { it.shortcutId == null && it.packageName != null }

        // An empty survey against a non-empty set of rows is a failed PackageManager query, not a
        // device with no apps on it. Same guard as the ROM reconciler's half-mounted card: bail
        // rather than flag the whole card missing.
        if (installedPackages.isEmpty() && rows.isNotEmpty()) {
            Timber.w("Empty installed-app survey against ${rows.size} app rows — skipping reconcile")
            return Result(0, 0, skipped = true)
        }

        // Only rows whose flag would actually change.
        val backAgain = rows.filter { it.isMissing && it.packageName in installedPackages }
        val goneNow = rows.filter { !it.isMissing && it.packageName !in installedPackages }

        if (backAgain.isNotEmpty()) {
            gameRepository.markAppsSeen(ANDROID_PLATFORM_ID, backAgain.packageNames(), now)
        }
        if (goneNow.isNotEmpty()) {
            gameRepository.markAppsMissing(ANDROID_PLATFORM_ID, goneNow.packageNames())
        }
        if (backAgain.isNotEmpty() || goneNow.isNotEmpty()) {
            // The card's stored count only counts present rows, so it is stale the moment a flag
            // moves in either direction.
            memoryCardRepository.recountGames(ANDROID_PLATFORM_ID)
            Timber.i("App presence: ${backAgain.size} back, ${goneNow.size} uninstalled")
        }

        return Result(
            markedSeen = backAgain.size,
            markedMissing = goneNow.size,
            skipped = false,
        )
    }

    private fun List<Game>.packageNames(): List<String> = mapNotNull { it.packageName }
}
