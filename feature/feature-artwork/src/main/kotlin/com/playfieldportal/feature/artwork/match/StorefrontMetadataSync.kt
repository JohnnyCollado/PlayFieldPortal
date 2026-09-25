package com.playfieldportal.feature.artwork.match

import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.domain.model.TaskKind
import com.playfieldportal.core.ui.notification.BackgroundTaskCenter
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bulk storefront resolution and the one notification it produces (C23 T6, Phases 13 and 19).
 *
 * **Why one notification and not one per game.** A Windows library is hundreds of rows. Reporting
 * each one would bury every other notification in the tray and ring the cue hundreds of times,
 * which is the failure mode Phase 19 names outright. The run is a single task: it ticks progress
 * while it works — progress is transient and never written to history — and settles exactly once
 * with the aggregate.
 *
 * The counts it settles with are the ones that tell a user what to do next: how many were matched,
 * how many need them, how many nothing had, and which stores could not be reached. A store that
 * timed out is reported as UNAVAILABLE and never folded into "no match", because those two lead to
 * opposite actions — wait and retry, or match by hand.
 */
@Singleton
class StorefrontMetadataSync @Inject constructor(
    private val resolver: StorefrontMetadataResolver,
    private val tasks: BackgroundTaskCenter,
) {

    /** What a bulk run did, in the terms the tray row is written from. */
    data class Summary(
        val processed: Int = 0,
        val matched: Int = 0,
        val needsConfirmation: Int = 0,
        val noMatch: Int = 0,
        val unavailableStores: Set<Storefront> = emptySet(),
    ) {
        operator fun plus(resolution: StorefrontMetadataResolver.GameResolution): Summary {
            val linked = resolution.byStore.values.any { it is StorefrontMetadataResolver.Resolution.Linked }
            val unavailable = resolution.unavailableStores
            return copy(
                processed = processed + 1,
                matched = matched + if (linked) 1 else 0,
                needsConfirmation = needsConfirmation + if (!linked && resolution.needsConfirmation) 1 else 0,
                // Counted only when nothing else happened: a game a store could not be reached for
                // has not been established to be absent from it, and saying so would be a lie the
                // user would act on.
                noMatch = noMatch + if (!linked && !resolution.needsConfirmation && unavailable.isEmpty()) 1 else 0,
                unavailableStores = unavailableStores + unavailable,
            )
        }

        /**
         * The tray message. Only non-zero counts appear — "0 require confirmation" is noise on a
         * run where everything matched.
         */
        val message: String
            get() = buildList {
                add("$matched matched")
                if (needsConfirmation > 0) add("$needsConfirmation need confirmation")
                if (noMatch > 0) add("$noMatch with no storefront match")
                if (unavailableStores.isNotEmpty()) {
                    add(unavailableStores.joinToString(", ") { it.label } + " unavailable")
                }
            }.joinToString(" · ")

        /** True when at least one store could not be reached — a partial run, not a failed one. */
        val partial: Boolean get() = unavailableStores.isNotEmpty()
    }

    /**
     * Resolves [games] one at a time and settles a single tray row.
     *
     * Serial on purpose: each provider already queues and deduplicates its own requests, so
     * running games in parallel would buy nothing and would only make the throttle's spacing
     * harder to reason about.
     */
    suspend fun run(games: List<GameEntity>): Summary {
        if (games.isEmpty()) return Summary()

        tasks.start(
            id = TASK_ID,
            label = "Matching storefront metadata…",
            kind = TaskKind.METADATA,
            current = 0,
            total = games.size,
        )

        var summary = Summary()
        try {
            games.forEachIndexed { index, game ->
                summary += resolver.resolve(game)
                tasks.progress(TASK_ID, index + 1, games.size, detail = game.title)
            }
        } catch (e: Throwable) {
            // A cancelled or crashed run must not strand a row in RUNNING forever.
            tasks.fail(TASK_ID, "Stopped after ${summary.processed} of ${games.size}")
            Timber.w(e, "Storefront metadata sync stopped early")
            throw e
        }

        tasks.complete(
            id = TASK_ID,
            message = summary.message,
        )
        return summary
    }

    private companion object {
        /**
         * One stable id for the whole run, which is what makes the tray row replace itself rather
         * than pile up: a second sync writes over the first one's history row.
         */
        const val TASK_ID = "storefront-metadata-sync"
    }
}
