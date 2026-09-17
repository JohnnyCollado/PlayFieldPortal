package com.playfieldportal.feature.artwork.importer

/**
 * Orders the files linked to one multi-asset (game, kind) slot during Scan & Relink (D1, C21 task
 * 1.5). A delete or reorder renumbers a slot's record positions without renaming its files, so the
 * filename's ordinal and a record's `sort_order` can disagree after either — a file must keep its
 * OWN record's relative order, never inherit whichever record happens to sit at its ordinal today.
 *
 * A file with a prior record — matched by portable name — keeps that record's relative order; a
 * file with no prior record (foreign, or a fresh library with no records at all) sorts after them,
 * by its filename ordinal (`ArtworkFileNaming.ordinalOf`). The caller assigns final positions
 * `0..n-1` from this order.
 *
 * Pure, so the ordering is testable without a folder, a database, or relink's own walk.
 */
internal object RelinkSlotOrdering {

    /**
     * @param files the slot's files, in whatever order the folder listed them.
     * @param stemOf a file's portable-name stem (e.g. `"Halo_02"`) — the name a record is keyed on.
     * @param ordinalOf the position the filename encodes (`ArtworkFileNaming.ordinalOf`).
     * @param priorSortOrder the slot's prior record's `sort_order` for a stem (lowercased), or null
     *   when no prior record carries that name.
     * @return [files] reordered: files with a prior record first, ordered by that record's
     *   `sort_order`; then the rest, ordered by [ordinalOf]. The caller's index into this list is
     *   the final `sort_order` it should write.
     */
    fun <T> order(
        files: List<T>,
        stemOf: (T) -> String,
        ordinalOf: (T) -> Int,
        priorSortOrder: (String) -> Int?,
    ): List<T> {
        val withPrior = mutableListOf<Pair<Int, T>>()
        val withoutPrior = mutableListOf<T>()
        for (file in files) {
            val prior = priorSortOrder(stemOf(file).lowercase())
            if (prior != null) withPrior += prior to file else withoutPrior += file
        }
        return withPrior.sortedBy { it.first }.map { it.second } + withoutPrior.sortedBy(ordinalOf)
    }
}
