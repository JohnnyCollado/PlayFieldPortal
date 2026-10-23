package com.playfieldportal.feature.backup

import com.playfieldportal.core.archive.ZipLimits

/**
 * The one size limit a backup archive lives under, applied when it is made AND when it is read.
 *
 * Restore used to read with the generic archive defaults (512 entries, 128 MB) while a backup
 * bundled every artwork and media file with no limit, so an ordinary library produced a backup
 * that could never be restored (survey, docs/audits/codebase-survey-2026-10-22.md). The limits are
 * still bounded — a restore stages untrusted files to disk, and an unbounded archive is a zip bomb.
 *
 * Sized from the app's own caps: a single file may be a 128 MB ambience track
 * (UiMediaLimits.AUDIO_STAGE_MAX_BYTES), and a large library is a few thousand games with a handful
 * of art files each.
 */
object BackupLimits {
    val ZIP = ZipLimits(
        maxEntries = 20_000,
        maxEntryBytes = 160L * 1024 * 1024,
        maxTotalBytes = 2L * 1024 * 1024 * 1024,
    )

    /** True when an archive of this shape can be restored. */
    fun fits(entries: Int, totalBytes: Long, largestEntryBytes: Long): Boolean =
        entries <= ZIP.maxEntries && totalBytes <= ZIP.maxTotalBytes && largestEntryBytes <= ZIP.maxEntryBytes

    /** Why a backup was not made, in words the user can act on. */
    const val TOO_LARGE_MESSAGE =
        "This backup would be too large to restore (it can hold up to 20,000 files and 2 GB). " +
            "Remove unused artwork or media and try again."
}
