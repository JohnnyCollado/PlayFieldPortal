package com.playfieldportal.core.domain.artwork

/**
 * Asks for a scoped artwork relink after a library scan added games (C22 task T5).
 *
 * **Why this interface exists at all.** A rescan that adds a game should reconnect that game's
 * artwork without the user pressing anything — but `feature-library` does not depend on
 * `feature-artwork`, and giving it one so a scanner could call a relink directly would be a new
 * edge in the wrong direction. The scanner states *that* platforms gained games; what to do about
 * it is the artwork module's business.
 *
 * Implementations must be **fire-and-forget**: the scan must never wait on artwork work, and a
 * failure here must never fail the scan.
 */
fun interface ArtworkRelinkTrigger {

    /**
     * Relink only [platformIds]. An empty set means "nothing was added" and must do nothing at
     * all — not a full-library relink, which is what a caller passing everything would get and
     * what this deliberately cannot express.
     */
    fun relinkPlatforms(platformIds: Set<String>)

    companion object {
        /** For tests and for builds where no artwork implementation is bound. */
        val NoOp = ArtworkRelinkTrigger { }
    }
}
