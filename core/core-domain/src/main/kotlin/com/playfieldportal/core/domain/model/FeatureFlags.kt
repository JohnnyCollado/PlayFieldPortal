package com.playfieldportal.core.domain.model

/** Build-time switches for features that are parked, not removed. */
object FeatureFlags {
    /**
     * Backup & Restore (`.pfpbackup`). Off until the backup format is reworked: Settings no longer
     * lists it and its screen closes if reached another way. The code and route stay in place.
     */
    val BACKUP_RESTORE: Boolean = false
}
