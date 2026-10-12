package com.playfieldportal.core.domain.model

/**
 * The user's own word on where a Windows game came from. It overrides PFP's automatic guess from
 * the Steam owned-games list; a null label on a [Game] means Auto.
 */
enum class WindowsGameLabel {
    STEAM,      // owned on Steam: achievements come from the Steam account
    NON_STEAM,  // owned elsewhere (GOG, Epic, ...): never Steam-linked
    LOCAL;      // a local copy: achievements come from its Goldberg/GSE folder

    companion object {
        /** Null for a null or unrecognised stored value, so it reads as Auto. */
        fun fromName(name: String?): WindowsGameLabel? =
            entries.firstOrNull { it.name == name }
    }
}
