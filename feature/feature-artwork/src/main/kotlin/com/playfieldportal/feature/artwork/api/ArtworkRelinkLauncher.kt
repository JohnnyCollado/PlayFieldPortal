package com.playfieldportal.feature.artwork.api

/**
 * Starts a relink (C22 task T3). The seam exists so a ViewModel test can assert "this menu item
 * starts a relink" without a WorkManager instance.
 *
 * Distinct from `ArtworkRelinkTrigger` in core-domain, which is the *scan's* narrower view: there,
 * an empty platform set means "nothing was added, do nothing". Here an empty set means the whole
 * library, which is what a user asking for Relink Artwork means. The trigger's binding delegates
 * to this and keeps its own guard, so the two readings can never be confused.
 */
fun interface ArtworkRelinkLauncher {

    /**
     * Relinks [platformIds], or the whole library when empty. Returns immediately.
     *
     * No default value: a `fun interface`'s single abstract method cannot carry one, and the
     * interface has to stay a `fun interface` because `ArtworkModule` SAM-constructs it from a
     * lambda. [relinkAll] is the whole-library call instead.
     */
    fun relink(platformIds: Set<String>)
}

/**
 * Relinks the whole library — what a user choosing "Relink Artwork" means, spelled out so the
 * call site does not read as an empty set that someone forgot to fill in.
 */
fun ArtworkRelinkLauncher.relinkAll(): Unit = relink(emptySet())
