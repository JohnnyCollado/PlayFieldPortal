package com.playfieldportal.studio

import com.playfieldportal.themekit.IconEditorLayout
import com.playfieldportal.themekit.IconSlot

/**
 * The icon slots a theme made in the Studio can replace: [IconEditorLayout]'s list, the one the
 * launcher's Customize XMB Icons shows too (crossbar, the XMB item rows, console art and
 * physical-media art, in XMB order). Everything else in the registry (the coin medallions, the
 * status strip, media controls, Game Detail, notifications, menus) keeps the launcher's own art —
 * the Studio still draws it in the preview, but never lists, imports or exports it.
 */
object EditableSlots {

    val ALL: List<IconSlot> = IconEditorLayout.ALL

    private val byKey: Map<String, IconSlot> = ALL.associateBy { it.key }

    /** The editable slot for [key], or null for an unknown key or one the Studio leaves alone. */
    fun byKey(key: String): IconSlot? = byKey[key]

    fun isEditable(key: String): Boolean = key in byKey
}
