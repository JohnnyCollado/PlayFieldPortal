package com.playfieldportal.core.ui.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest

/**
 * A revision number per artwork URI, bumped whenever that URI's bytes are replaced in place.
 *
 * Evicting Coil's cache is not enough on its own. A portable-library write keeps the same file
 * name, so the same document URI, so the game column holds the same string: every `AsyncImage`
 * already showing it sees an unchanged model and never asks again. It keeps the old bitmap until
 * it leaves composition. Reading the revision here is what makes a composable recompose when the
 * bytes behind its URI change.
 *
 * Snapshot state in a process-wide object rather than an injected class, because the readers are
 * dozens of composables across modules that have no ViewModel path to a singleton. It holds
 * display bookkeeping only; nothing is persisted.
 */
object ArtworkRevisions {
    private val revisions = mutableStateMapOf<String, Int>()

    /** Marks [uri]'s bytes as replaced. Safe from any thread. */
    fun bump(uri: String) {
        revisions[uri] = (revisions[uri] ?: 0) + 1
    }

    /** The current revision of [uri]; 0 until its bytes are first replaced. */
    fun of(uri: String): Int = revisions[uri] ?: 0

    /**
     * The memory-cache key for [uri]'s current bytes, or null while it has never been replaced
     * (null lets Coil derive its usual key). For call sites that build their own `ImageRequest`.
     */
    fun cacheKey(uri: String): String? = of(uri).takeIf { it > 0 }?.let { "$uri#r$it" }
}

/**
 * The model to give `AsyncImage` for artwork that can be rewritten at the same URI.
 *
 * Revision 0 is the plain URI, exactly what call sites passed before. After a bump it becomes a
 * request whose memory-cache key carries the revision, so the model changes (the image reloads) and
 * the lookup can never land on a bitmap cached for an older revision.
 */
@Composable
fun rememberArtworkModel(uri: String?): Any? {
    if (uri == null) return null
    val key = ArtworkRevisions.cacheKey(uri) ?: return uri
    val context = LocalPlatformContext.current
    return remember(key, context) {
        ImageRequest.Builder(context)
            .data(uri)
            .memoryCacheKey(key)
            .build()
    }
}
