package com.playfieldportal.core.domain.model

// A user-configured console library. Each card is one platform reading from exactly
// one directory, with one assigned emulator. Cards are created manually — nothing is
// detected or scanned automatically. Inside the Games category each enabled card is
// shown as a Memory Card the user can open like a PSP folder.
data class MemoryCard(
    val platformId: String,                 // catalog platform this card represents (PK)
    val displayName: String,                // e.g. "PSP Memory Card"
    val enabled: Boolean = true,            // shown inside Games when true
    val pinned: Boolean = false,            // sorts above non-pinned cards
    val sortOrder: Int = 0,                 // manual ordering within its pinned group
    val romDirectory: String? = null,       // the single directory this card scans
    val supportedExtensions: List<String> = emptyList(), // lowercase, no dots
    val emulatorId: String? = null,         // EmulatorProfile.id assigned to launches
    val scanRecursively: Boolean = true,
    val lastScannedAt: Long? = null,
    val gameCount: Int = 0,
)
