package com.playfieldportal.core.data.repository

/**
 * The seam that lets core-data evict Coil's image cache without depending on a feature
 * module. Replaced GIFs live at stable paths (the slot key IS the filename), and Coil's
 * path-keyed cache keeps serving the old bytes after an overwrite — so every successful
 * import must evict. The app module binds this to ArtworkImageCache.evict; tests record.
 */
fun interface CustomIconCacheEvictor {
    fun evict(path: String)
}
