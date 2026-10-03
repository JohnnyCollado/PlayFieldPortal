package com.playfieldportal.feature.xmb.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.playfieldportal.feature.xmb.viewmodel.XMBItem
import com.playfieldportal.feature.xmb.viewmodel.XMBItemType
import kotlinx.coroutines.delay

// How long a focused UMD slot stays the UMD glyph before the inserted game shows — the beat the
// PSP spends reading the disc before ICON0 and PIC1 appear.
internal const val UMD_READ_DELAY_MS = 800L

/**
 * What identifies one read of the focused UMD slot, or null when [focused] is not the slot. Every
 * column's slot shares a row id, so the column and the inserted game key it: stepping straight from
 * one column's UMD to another's starts a fresh read.
 */
internal fun umdReadKey(focused: XMBItem?, columnIndex: Int): Any? =
    focused?.takeIf { it.type == XMBItemType.UMD_SLOT }?.let { columnIndex to it.gameId }

/** [this] as the focused row's art source: a UMD slot not yet read has none. */
internal fun XMBItem.afterUmdRead(read: Boolean): XMBItem? =
    takeUnless { type == XMBItemType.UMD_SLOT && !read }

/** Whether the read keyed by [key] (see [umdReadKey]) has finished. False while [key] is null. */
@Composable
internal fun rememberUmdRead(key: Any?): Boolean {
    var read by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        if (key != null) {
            delay(UMD_READ_DELAY_MS)
            read = true
        }
    }
    return read
}
