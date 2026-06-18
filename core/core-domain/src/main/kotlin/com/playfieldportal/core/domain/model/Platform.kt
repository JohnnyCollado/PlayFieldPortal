package com.playfieldportal.core.domain.model

data class Platform(
    val id: String,                     // e.g. "ps2", "gba", "android"
    val name: String,                   // e.g. "PlayStation 2"
    val shortName: String,              // e.g. "PS2"
    val iconRes: String?,               // built-in drawable resource name
    val accentColor: Long,              // ARGB color for wave tint
    val isPinnedToBar: Boolean = false, // user promoted to XMB category bar
    val barPosition: Int = -1,          // position on bar when pinned
    val preferredEmulatorPackage: String? = null,
    val romExtensions: List<String> = emptyList(), // e.g. [".iso", ".cso"]
)
