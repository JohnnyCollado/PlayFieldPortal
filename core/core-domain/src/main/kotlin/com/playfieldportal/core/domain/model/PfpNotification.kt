package com.playfieldportal.core.domain.model

/**
 * One durable entry in the notification panel's EARLIER section — a fact about the library that
 * outlives the work that produced it.
 *
 * Running work is deliberately NOT one of these (see the panel plan §4.2): in-flight progress is
 * process state, so a crash mid-scan would otherwise strand a phantom "Scanning… 40%" row with
 * nothing alive left to finish or fail it, and every progress tick would be a database write. A
 * task writes exactly one of these, once, when it settles.
 */
data class PfpNotification(
    val id: Long,
    val kind: NotificationKind,
    val severity: NotificationSeverity,
    val title: String,
    val body: String? = null,
    /**
     * Dedupe key, e.g. `"scan:memcard_psx"`. A post carrying one replaces the row that already
     * holds it and resets its timestamps — a Memory Card that fails four times is one unread row,
     * not four. Null posts always append.
     */
    val sourceKey: String? = null,
    val action: NotificationAction = NotificationAction.None,
    /**
     * The row's [NotificationDetail] as JSON (see [NotificationDetailCodec]): Notes or Results.
     * Null for a Simple row and for every row written before details existed.
     */
    val payload: String? = null,
    val createdAt: Long,
    val readAt: Long? = null,
) {
    val isRead: Boolean get() = readAt != null

    /**
     * What Confirm opens. A stored payload wins; failing that, a body with no action to run becomes a
     * one-section note, so every row with something to say stays readable — including rows written
     * before payloads existed, whose body was only reachable through the old info dialog.
     */
    val detail: NotificationDetail? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        NotificationDetailCodec.decode(payload)
            ?: body?.takeIf { it.isNotBlank() && action == NotificationAction.None }
                ?.let { NotificationDetail.Notes(summary = it) }
    }

    val depth: NotificationDepth
        get() = when (detail) {
            is NotificationDetail.Notes -> NotificationDepth.NOTES
            is NotificationDetail.Results -> NotificationDepth.RESULTS
            null -> NotificationDepth.SIMPLE
        }

    /**
     * The one line the panel shows. Titles stand alone now that the body lives in the sheet, but a
     * Simple row with an action has no sheet, so its body rides along after a dash rather than vanish.
     */
    val displayTitle: String
        // endsWith: rows recorded before the split already carry "title — body" in the title.
        get() = if (depth == NotificationDepth.SIMPLE && !body.isNullOrBlank() && !title.endsWith(body)) {
            "$title — $body"
        } else {
            title
        }
}

/**
 * What produced the row. Overlaps the running-task kinds on purpose: a running ARTWORK task settles
 * into an ARTWORK history row, so one icon table serves both sections of the panel.
 */
enum class NotificationKind {
    SCAN, ARTWORK, METADATA, ACHIEVEMENT, LAUNCH, SYSTEM, DOWNLOAD, FEED;

    companion object {
        /** Unknown names (an older row, a newer build's kind) read back as SYSTEM rather than throwing. */
        fun fromName(name: String?): NotificationKind =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/** Carried by the row's ring colour and tint, never by different art — see the plan's icon set. */
enum class NotificationSeverity {
    INFO, SUCCESS, WARNING, ERROR;

    companion object {
        fun fromName(name: String?): NotificationSeverity =
            entries.firstOrNull { it.name == name } ?: INFO
    }
}

/**
 * Where ✕ on a history row goes. Stored as a (type, arg) pair rather than serialized JSON so the
 * column stays readable in a database dump and a new action never needs a migration.
 *
 * An unrecognised type decodes to [None], which degrades to the read-only info dialog. That is the
 * behaviour a downgrade needs: a row written by a newer build must not crash an older one.
 */
sealed interface NotificationAction {

    val typeKey: String
    val arg: String? get() = null

    data object None : NotificationAction {
        override val typeKey = TYPE_NONE
    }

    data class OpenCategory(val categoryId: String) : NotificationAction {
        override val typeKey = TYPE_CATEGORY
        override val arg = categoryId
    }

    data class OpenMemoryCard(val platformId: String) : NotificationAction {
        override val typeKey = TYPE_MEMORY_CARD
        override val arg = platformId
    }

    data class OpenGame(val gameId: Long) : NotificationAction {
        override val typeKey = TYPE_GAME
        override val arg = gameId.toString()
    }

    data class OpenSettingsScreen(val routeId: String) : NotificationAction {
        override val typeKey = TYPE_SETTINGS
        override val arg = routeId
    }

    /** Opens the in-launcher confirm for a pending INSTALL_SHORTCUT request (the shade Add/Ignore it replaced). */
    data class ReviewShortcut(val requestId: String) : NotificationAction {
        override val typeKey = TYPE_REVIEW_SHORTCUT
        override val arg = requestId
    }

    /** The only action that leaves the app. Unimplemented until the RSS channel lands (plan §8). */
    data class OpenUrl(val url: String) : NotificationAction {
        override val typeKey = TYPE_URL
        override val arg = url
    }

    companion object {
        const val TYPE_NONE = "none"
        const val TYPE_CATEGORY = "open_category"
        const val TYPE_MEMORY_CARD = "open_memory_card"
        const val TYPE_GAME = "open_game"
        const val TYPE_SETTINGS = "open_settings"
        const val TYPE_URL = "open_url"
        const val TYPE_REVIEW_SHORTCUT = "review_shortcut"

        fun decode(typeKey: String?, arg: String?): NotificationAction = when (typeKey) {
            TYPE_CATEGORY -> arg?.let(::OpenCategory) ?: None
            TYPE_MEMORY_CARD -> arg?.let(::OpenMemoryCard) ?: None
            TYPE_GAME -> arg?.toLongOrNull()?.let(::OpenGame) ?: None
            TYPE_SETTINGS -> arg?.let(::OpenSettingsScreen) ?: None
            TYPE_URL -> arg?.let(::OpenUrl) ?: None
            TYPE_REVIEW_SHORTCUT -> arg?.let(::ReviewShortcut) ?: None
            else -> None
        }
    }
}
