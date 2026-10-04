package com.playfieldportal.core.data.repository

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences

/**
 * The "something on disk changed, reload" stamps (UI media, custom icons, theme icons) are
 * observed for a CHANGE, so two edits inside one millisecond must still produce two values.
 * Clock-only stamps did not: an import followed by a clear in the same tick left the stamp equal,
 * and observers kept the stale listing. Always strictly after whatever is stored.
 */
internal fun MutablePreferences.bumpStamp(key: Preferences.Key<Long>) {
    this[key] = nextStamp(this[key])
}

internal fun nextStamp(previous: Long?, now: Long = System.currentTimeMillis()): Long =
    if (previous == null) now else maxOf(now, previous + 1)
