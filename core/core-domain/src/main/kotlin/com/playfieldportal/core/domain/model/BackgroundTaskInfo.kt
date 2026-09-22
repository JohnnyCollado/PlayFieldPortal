package com.playfieldportal.core.domain.model

/**
 * A unit of background work, live and in memory.
 *
 * This is the RUNNING half of the notification panel and deliberately never becomes a database
 * row (plan §4.2): a force-stop mid-scan would otherwise strand a phantom "Scanning… 40%" row
 * forever, and a scrape of 800 games would be 800 writes for data that is worthless a second later.
 *
 * [current] and [total] used to be computed into a fraction at the call site and thrown away, which
 * cost the panel — and the shade notification — the "14 / 56" that the producers already report.
 * The operands are carried now and [fraction] derives the bar, so nothing downstream had to change.
 */
data class BackgroundTaskInfo(
    val id: String,
    val label: String,
    val kind: TaskKind = TaskKind.SCAN,
    val current: Int? = null,
    val total: Int? = null,
    /** The item being worked on right now, e.g. the artwork title being fetched. */
    val detail: String? = null,
) {
    /**
     * 0..1 for a determinate bar, or null for an indeterminate one.
     *
     * Null whenever either operand is missing — a file scan reports no total at all today (plan
     * §4.3) — and when [total] is 0, which is a producer saying "nothing to do", not "divide by
     * zero".
     */
    val fraction: Float?
        get() = if (current != null && total != null && total > 0) {
            (current.toFloat() / total).coerceIn(0f, 1f)
        } else {
            null
        }

    /** `"14 / 56"` beside the bar, or null when the producer reports no counts. */
    val countLabel: String?
        get() = if (current != null && total != null && total > 0) "$current / $total" else null
}

/**
 * What kind of work is running. Mirrors [NotificationKind] so the row a task settles into reuses
 * the same icon table — a running ARTWORK task becomes an ARTWORK history row.
 */
enum class TaskKind {
    SCAN, ARTWORK, METADATA, ACHIEVEMENT, DOWNLOAD;

    val notificationKind: NotificationKind
        get() = when (this) {
            SCAN -> NotificationKind.SCAN
            ARTWORK -> NotificationKind.ARTWORK
            METADATA -> NotificationKind.METADATA
            ACHIEVEMENT -> NotificationKind.ACHIEVEMENT
            DOWNLOAD -> NotificationKind.DOWNLOAD
        }
}
