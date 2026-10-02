package com.playfieldportal.core.domain.model

/**
 * What a gaming column's UMD slot shows (Settings ▸ Interface ▸ Display ▸ UMD Slot).
 *
 *  - [OFF]                 no slot at all, and no Insert / Eject in the options menus.
 *  - [INSERTED]            only a game you inserted; nothing otherwise.
 *  - [INSERTED_AND_RECENT] the inserted game, else the column's most recently played one. The default.
 */
enum class UmdSlotMode(val label: String) {
    OFF("Off"),
    INSERTED("Inserted"),
    INSERTED_AND_RECENT("Inserted & Recent");

    companion object {
        val DEFAULT = INSERTED_AND_RECENT

        /** Tolerant parse for the persisted preference; unknown/blank falls back to [DEFAULT]. */
        fun fromName(value: String?): UmdSlotMode =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
