package com.playfieldportal.core.data.repository

/**
 * What PFP actually knows about the libretro cores installed in RetroArch.
 *
 * RetroArch keeps cores in private internal storage, so the only way to know what exists is the
 * user's SAF grant ([RetroArchLink]). PFP used to model this as `Set<String>?`, where null meant
 * "no idea" — and the one caller that saw null answered by *inventing* a list of nineteen cores the
 * user probably had. Those invented profiles were persisted as available, won the automatic
 * platform default, and launched RetroArch with a core path that did not exist: a black screen.
 *
 * Naming the states removes the guess and, more importantly, makes [isAuthoritative] explicit.
 *
 * **The invariant: never mutate stored user configuration from a non-authoritative inventory.**
 * Only [Verified] reflects a live read of the real directory. Every other state means PFP could not
 * check, and a state that cannot confirm a core is present equally cannot conclude one is absent —
 * so it may change what is *offered*, never what is *stored*. Without this rule a lost SAF grant
 * (a RetroArch reinstall is enough) would let a routine startup pass delete profiles and cascade
 * into the platform defaults, memory-card assignments, and per-game overrides that reference them.
 */
sealed interface CoreInventory {

    /** Core file names, e.g. `mgba_libretro_android.so`. Empty when nothing is known. */
    val coreFiles: Set<String>

    /** True only for a live read of the granted tree — see the invariant above. */
    val isAuthoritative: Boolean

    /** No tree has ever been linked. PFP knows nothing; offer nothing and ask the user to link. */
    data object Unlinked : CoreInventory {
        override val coreFiles: Set<String> = emptySet()
        override val isAuthoritative: Boolean = false
    }

    /** A live read of the granted `cores` directory. The only state trusted to prove absence. */
    data class Verified(override val coreFiles: Set<String>) : CoreInventory {
        override val isAuthoritative: Boolean = true
    }

    /**
     * The grant is live but the folder holds no libretro cores — nearly always the wrong folder
     * (RetroArch's `system` or `downloads` rather than its data dir). Deliberately NOT authoritative:
     * a mis-pick must not be read as "you have no cores" and take the user's setup down with it.
     */
    data object EmptyTree : CoreInventory {
        override val coreFiles: Set<String> = emptySet()
        override val isAuthoritative: Boolean = false
    }

    /**
     * The tree is stored but its OS grant is gone (grants do not survive a RetroArch reinstall), so
     * this is the last inventory that was successfully read. Good enough to keep offering the user's
     * cores; never good enough to delete anything. The UI should prompt for a re-link.
     */
    data class Remembered(override val coreFiles: Set<String>) : CoreInventory {
        override val isAuthoritative: Boolean = false
    }
}
