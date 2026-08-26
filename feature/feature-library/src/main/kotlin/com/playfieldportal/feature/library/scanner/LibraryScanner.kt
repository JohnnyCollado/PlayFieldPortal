package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.data.repository.LibraryReconciler
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.MemoryCard
import com.playfieldportal.core.domain.repository.GameRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

// See docs/adr/0001-library-scanner-owns-rom-survey.md.

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ScannerIoDispatcher

@Module
@InstallIn(SingletonComponent::class)
object LibraryScannerModule {
    @Provides
    @ScannerIoDispatcher
    fun provideScannerIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}

enum class ScanStatus { COMPLETED, SKIPPED_NO_SOURCE, SKIPPED_BUSY, FAILED }

/**
 * Result of surveying one Memory Card. No Android/Compose types — both the settings ViewModel
 * and the headless rescan coordinator map this into their own presentation.
 *
 * [surveyTrusted] is true only when a survey completed trustworthily (the COMPLETED outcome
 * computes it from scan errors and the present set). Every other outcome — skipped, busy, or
 * failed — defaults to false because no trustworthy survey ran.
 */
data class PlatformScanOutcome(
    val platformId: String,
    val displayName: String,
    val status: ScanStatus,
    val added: Int = 0,
    val markedMissing: Int = 0,
    val surveyTrusted: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Whether a Memory Card is eligible for a scan pass: enabled, with a configured ROM source and at
 * least one supported extension. Shared by [LibraryScanner.scanAllEnabled] and the settings
 * screen's "Scan All" so the two bulk entry points can't drift on eligibility.
 */
fun MemoryCard.isScannable(): Boolean =
    enabled && (!treeUri.isNullOrBlank() || !romDirectory.isNullOrBlank()) && supportedExtensions.isNotEmpty()

/**
 * Owns the ROM survey and Missing-reconciliation policy for a Memory Card: resolves its sources,
 * seeds the existing-path set, upserts newly discovered games exactly once, unions present paths,
 * and optionally reconciles Missing rows. [LibraryManagerViewModel] and [LibraryRescanCoordinator]
 * both call in here instead of keeping their own copy of this loop — see the ADR for why.
 */
@Singleton
class LibraryScanner @Inject constructor(
    private val memoryCardRepository: MemoryCardRepository,
    private val gameRepository: GameRepository,
    private val scanSourceResolver: ScanSourceResolver,
    private val existingRomPathResolver: ExistingRomPathResolver,
    private val libraryReconciler: LibraryReconciler,
    private val discSetReconciler: DiscSetReconciler,
    @ScannerIoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    // Per-card single-flight, shared across every caller (manual scans, resume, mount, unplug).
    // A card already mid-survey returns SKIPPED_BUSY rather than queuing or racing a second walk
    // against the same DB rows.
    private val busyPlatforms = ConcurrentHashMap.newKeySet<String>()

    /** Surveys one Memory Card. See [ScanStatus] for the outcomes a caller must handle. */
    suspend fun scanPlatform(platformId: String, removeMissing: Boolean): PlatformScanOutcome {
        val card = memoryCardRepository.getById(platformId)
            ?: return PlatformScanOutcome(
                platformId  = platformId,
                displayName = platformId,
                status      = ScanStatus.SKIPPED_NO_SOURCE,
                errorMessage = "Memory Card not found.",
            )

        if (!busyPlatforms.add(platformId)) {
            return PlatformScanOutcome(platformId, card.displayName, ScanStatus.SKIPPED_BUSY)
        }
        return try {
            withContext(ioDispatcher) { scanLocked(card, removeMissing) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Timber.e(e, "Library scan failed for $platformId")
            PlatformScanOutcome(
                platformId, card.displayName, ScanStatus.FAILED,
                errorMessage = e.message ?: "Scan failed — see the log.",
            )
        } finally {
            busyPlatforms.remove(platformId)
        }
    }

    /**
     * Scans every enabled, ROM-source-backed, extension-configured Memory Card, sequentially and
     * deterministically. A card that fails becomes an outcome; it never aborts the remaining
     * cards.
     */
    suspend fun scanAllEnabled(removeMissing: Boolean): List<PlatformScanOutcome> {
        val eligible = memoryCardRepository.getAll().filter { it.isScannable() }
        return eligible.map { scanPlatform(it.platformId, removeMissing) }
    }

    private suspend fun scanLocked(card: MemoryCard, removeMissing: Boolean): PlatformScanOutcome {
        val platformId = card.platformId

        // An incomplete existing-path set is unsafe for upserts or Missing reconciliation — a
        // failed DB read fails the card before source resolution or any folder walk starts.
        // Falling back to an empty set (the old behavior) could duplicate already-known games.
        val baseline = try {
            existingRomPathResolver.baselineFor(platformId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Timber.e(e, "Library scan — existing-path baseline failed for $platformId")
            return PlatformScanOutcome(
                platformId, card.displayName, ScanStatus.FAILED,
                errorMessage = e.message ?: "Could not read the library.",
            )
        }
        val dbGames = baseline.games

        val sources = scanSourceResolver.sourcesFor(card)
        if (sources.isEmpty()) {
            return PlatformScanOutcome(platformId, card.displayName, ScanStatus.SKIPPED_NO_SOURCE)
        }

        // Live, growing set so a ROM already added from one source isn't re-added from another.
        val existing = baseline.romPaths.toMutableSet()

        var added = 0
        var scanErrored = false
        var firstSourceError: String? = null
        // The newly discovered rows, as enriched by the scanner's single-pass assign — the fresh
        // half of the disc-set reconcile below.
        val scannedGames = mutableListOf<Game>()
        // Union of on-disk ROM paths across all sources; null once any source can't survey.
        var present: MutableSet<String>? = mutableSetOf()
        var firstWriteFailure: String? = null

        sourceLoop@ for (source in sources) {
            source(existing).collect { result ->
                when (result) {
                    is ScanResult.Complete -> {
                        scannedGames.addAll(result.newGames)
                        for (game in result.newGames) {
                            try {
                                gameRepository.upsert(game)
                                game.romPath?.let(existing::add)
                                added++
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (e: Exception) {
                                Timber.e(e, "Library scan — upsert failed for $platformId")
                                if (firstWriteFailure == null) {
                                    firstWriteFailure = e.message ?: "Could not save a scanned game."
                                }
                            }
                        }
                        result.presentRomPaths?.let { paths -> present?.addAll(paths) } ?: run { present = null }
                    }
                    is ScanResult.Error -> {
                        scanErrored = true
                        if (firstSourceError == null) firstSourceError = result.message
                    }
                    else -> Unit
                }
            }
            // A write failure keeps everything already saved but stops surveying further sources
            // and skips reconciliation — an incomplete survey must not drive Missing removals.
            if (firstWriteFailure != null) break@sourceLoop
        }

        if (firstWriteFailure != null) {
            return PlatformScanOutcome(
                platformId   = platformId,
                displayName  = card.displayName,
                status       = ScanStatus.FAILED,
                added        = added,
                surveyTrusted = false,
                errorMessage = firstWriteFailure,
            )
        }

        // Disc-set reconcile (docs/plans/multi-disc-games-plan.md follow-up): the scanner only
        // enriched the newly added rows against themselves, so a disc arriving into an
        // already-scanned .m3u set (or a new .m3u adopting existing discs) needs the union
        // re-derived. Deterministic and idempotent — only rows whose disc fields changed are
        // rewritten, and a failure here is non-fatal (the next scan re-derives the same union).
        discSetReconciler.reconcilePlatform(platformId, dbGames, scannedGames)

        // Non-destructive reconcile: present files are marked seen, gone files are marked
        // missing (never deleted). The reconciler internally skips removals when the survey is
        // untrustworthy — scan error, no survey, or an empty survey against a non-empty library.
        var removed = 0
        if (removeMissing) {
            removed = libraryReconciler.reconcile(dbGames, present, scanErrored).markedMissing
        }

        if (added > 0 || removed > 0) {
            memoryCardRepository.recordScan(platformId, System.currentTimeMillis())
            memoryCardRepository.recountGames(platformId)
        }

        Timber.i("Library scan complete for $platformId: $added new, $removed marked missing")
        return PlatformScanOutcome(
            platformId    = platformId,
            displayName   = card.displayName,
            status        = ScanStatus.COMPLETED,
            added         = added,
            markedMissing = removed,
            surveyTrusted = !scanErrored && present != null,
            errorMessage  = if (scanErrored) firstSourceError else null,
        )
    }
}

/** Formats a per-platform scan result consistently for every scan entry point. */
fun scanOutcomeMessage(outcome: PlatformScanOutcome, removeMissing: Boolean): String =
    when (outcome.status) {
        ScanStatus.SKIPPED_NO_SOURCE ->
            "${outcome.displayName}: ${outcome.errorMessage ?: "ROM folder not configured."}"
        ScanStatus.SKIPPED_BUSY -> "${outcome.displayName}: scan already in progress."
        ScanStatus.FAILED -> "${outcome.displayName}: ${outcome.errorMessage ?: "scan failed."}"
        ScanStatus.COMPLETED ->
            "${outcome.displayName}: " + buildString {
                append(if (outcome.added == 0) "no new ROMs" else "${outcome.added} new ROM(s) added")
                if (removeMissing) {
                    append(if (outcome.markedMissing == 0) ", none missing" else ", ${outcome.markedMissing} marked missing")
                }
                outcome.errorMessage?.let { append(" ($it)") }
            }
    }
