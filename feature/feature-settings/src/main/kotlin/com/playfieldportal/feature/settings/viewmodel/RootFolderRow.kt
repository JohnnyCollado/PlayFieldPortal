package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.data.repository.RomRootRepository

/**
 * One managed ROOT folder in a settings "Root Access" section (Library ROM roots, Music/Video/Photo
 * roots). [linked] is the live SAF-grant status — false after a wipe/reinstall until re-linked.
 */
data class RootFolderRow(
    val treeUri: String,
    val name: String,
    val linked: Boolean,
)

/** Human-readable label for a root tree URI: its raw path when derivable, else the URI tail. */
fun rootDisplayName(treeUri: String): String =
    RomRootRepository.rawPathOfTree(treeUri)
        ?: treeUri.substringAfterLast('/').ifBlank { treeUri }
