package com.playfieldportal.feature.artwork.api

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * The single owner of Coil's display caches for artwork: it installs the app's configured
 * [ImageLoader] as Coil's singleton, and it is the only thing that evicts from or clears it.
 *
 * Those two jobs live together deliberately. `AsyncImage` and `Context.imageLoader` always resolve
 * through Coil's singleton, which — until something installs one — is a default loader with a
 * default disk cache. This app configures its own loader in [ArtworkModule] (512 MB cache under
 * `artwork_cache`) and injects it, but never installed it, so displaying went through Coil's
 * default loader while evicting went through the configured one. Re-scraped art kept serving the
 * old bytes from the path-keyed cache, "clear artwork cache" freed nothing the UI was reading, and
 * the reported cache size measured a cache nothing displayed from.
 *
 * Keeping the factory and the eviction calls in one class is what stops them drifting apart again:
 * there is no longer a second loader for a caller to reach for.
 */
@Singleton
class ArtworkImageCache @Inject constructor(
    // A Provider, not the loader itself: building the loader opens its disk cache, and that should
    // happen on the first image request rather than during Application.onCreate.
    private val imageLoader: Provider<ImageLoader>,
) {

    /**
     * Hands Coil the configured loader. Call once from `Application.onCreate`, before anything can
     * request an image — `setSafe` declines to replace a singleton that has already been built, so
     * installing late would silently leave Coil on its default loader.
     */
    fun installAsSingleton() {
        SingletonImageLoader.setSafe { imageLoader.get() }
    }

    /**
     * Drops [uris] from the memory and disk caches. Scraped files reuse stable filenames, so a
     * re-scraped image must be evicted or the path-keyed cache keeps serving the old bytes.
     * Nothing on disk or in the database is touched — this is display cache only.
     */
    fun evict(uris: Collection<String>) {
        val loader = imageLoader.get()
        uris.forEach { uri ->
            loader.memoryCache?.remove(MemoryCache.Key(uri))
            loader.diskCache?.remove(uri)
        }
    }

    /** Convenience for the single-URI case (a portable write replacing bytes at a stable URI). */
    fun evict(uri: String) = evict(listOf(uri))

    /** Bytes Coil is currently holding on disk for artwork. */
    fun diskSizeBytes(): Long = imageLoader.get().diskCache?.size ?: 0L

    /** Empties both caches. Stored artwork files and database rows are untouched. */
    fun clear() {
        val loader = imageLoader.get()
        loader.diskCache?.clear()
        loader.memoryCache?.clear()
    }
}
