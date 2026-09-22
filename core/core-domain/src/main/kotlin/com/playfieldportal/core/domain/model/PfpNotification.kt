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
    /** Opaque JSON, unused today. The seam a later RSS item uses for its enclosure url/size/mime. */
    val payload: String? = null,
    val createdAt: Long,
    val readAt: Long? = null,
) {
    val isRead: Boolean get() = readAt != null
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

        fun decode(typeKey: String?, arg: String?): NotificationAction = when (typeKey) {
            TYPE_CATEGORY -> arg?.let(::OpenCategory) ?: None
            TYPE_MEMORY_CARD -> arg?.let(::OpenMemoryCard) ?: None
            TYPE_GAME -> arg?.toLongOrNull()?.let(::OpenGame) ?: None
            TYPE_SETTINGS -> arg?.let(::OpenSettingsScreen) ?: None
            TYPE_URL -> arg?.let(::OpenUrl) ?: None
            else -> None
        }
    }
}
