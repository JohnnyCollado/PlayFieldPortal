package com.playfieldportal.core.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Cosmetic display names for SAF picks. PFP copies every user media customization into
 * `filesDir` (see UiMediaStore), so the original URI is only alive for the moment of import —
 * the name queried here is stored alongside the copy and survives restarts.
 *
 * NEVER render a raw `content://…` string in UI: the resolver can return null for cloud-backed
 * or oddball providers, and the raw form is both unreadable and (uniquely among the strings a
 * picker hands us) attacker-shaped — a malicious provider controls it entirely, so it is treated
 * as untrusted display input, length-clamped before it reaches a label.
 */
object MediaDisplayNames {

    /** A display name is a label, not data — nothing on screen needs more than this. */
    const val MAX_LENGTH = 64

    /**
     * Queries [uri]'s display name, or null when the provider doesn't report one. Results are
     * cached in memory: the same URI is often queried for several slots in one import session,
     * and each query is a binder round-trip into a provider we do not control.
     */
    private val cache = HashMap<String, String?>()

    fun queryDisplayName(context: Context, uri: Uri): String? {
        val key = uri.toString()
        cache[key]?.let { return it }
        if (cache.containsKey(key)) return cache[key]   // null is a cached answer too

        val queried = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && !cursor.isNull(idx)) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        }.getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { if (it.length > MAX_LENGTH) it.take(MAX_LENGTH) + "…" else it }

        cache[key] = queried
        return queried
    }

    /** For test isolation — the cache is process-global. */
    fun clearCache() = cache.clear()
}
