package com.playfieldportal.studio

import com.playfieldportal.themekit.CustomizableIcons
import com.playfieldportal.themekit.IconSlot

/**
 * The icon slots a theme made in the Studio can replace: the crossbar, the XMB item glyphs and
 * the console art. Everything else in [CustomizableIcons] (Shiba Coins rows and medallions, the
 * status strip, media controls, Game Detail, notifications, menus) keeps the launcher's own art —
 * the Studio still draws it in the preview, but never lists, imports or exports it.
 */
object EditableSlots {

    private val NOT_THEMEABLE_GROUPS = setOf(
        IconSlot.Group.STATUS,
        IconSlot.Group.SHIBA,
        IconSlot.Group.MEDIA,
        IconSlot.Group.GAME_DETAIL,
        IconSlot.Group.NOTIFICATIONS,
        IconSlot.Group.MENUS,
    )

    fun isEditable(slot: IconSlot): Boolean =
        slot.group !in NOT_THEMEABLE_GROUPS && !slot.key.startsWith("item_shiba_")

    val ALL: List<IconSlot> = CustomizableIcons.ALL.filter(::isEditable)

    private val byKey: Map<String, IconSlot> = ALL.associateBy { it.key }

    /** The editable slot for [key], or null for an unknown key or one the Studio leaves alone. */
    fun byKey(key: String): IconSlot? = byKey[key]

    fun isEditable(key: String): Boolean = key in byKey

    fun group(group: IconSlot.Group): List<IconSlot> = ALL.filter { it.group == group }
}
