package com.playfieldportal.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.studio.ExportCheck
import com.playfieldportal.studio.StudioState
import com.playfieldportal.studio.io.PtfConversion

/**
 * The Studio's sections, in rail order. [id] is the stable key the shell and the section panels
 * (TS-28..30) address a section by; the order here IS the rail order.
 */
enum class StudioSection(val id: String, val label: String) {
    INFO("info", "Info"),
    COLOR("color", "Color"),
    BACKGROUND("background", "Background"),
    LEGIBILITY("legibility", "Legibility"),
    LAYOUT("layout", "Layout"),
    ICONS("icons", "Icons"),
    SOUNDS("sounds", "Sounds"),
    BOOT("boot", "Boot & GameBoot"),
    EXPORT_CHECK("export", "Export check"),
    ;

    companion object {
        val DEFAULT: StudioSection = INFO

        fun fromId(id: String?): StudioSection = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

private val BOOT_KEYS = setOf("boot_video", "gameboot_video")

/** One-line status under a rail label: what this section currently holds. Pure. */
fun railMeta(section: StudioSection, state: StudioState, check: ExportCheck): String = when (section) {
    StudioSection.INFO -> state.name.trim().ifEmpty { "Untitled" }
    StudioSection.COLOR -> PtfConversion.toHexRgb(state.accentArgb)
    StudioSection.BACKGROUND -> when {
        state.motionFile != null -> "Video"
        state.wallpaperPng != null -> "Image"
        else -> "Wave"
    }
    StudioSection.LEGIBILITY -> if (state.legibility == null) "Not set" else "Set"
    // The theme carries no XMB sizes; this section only previews the device's own layout adjust.
    StudioSection.LAYOUT -> "Preview only"
    StudioSection.ICONS -> {
        val n = state.iconOverrides.size + state.sysiconOverrides.size
        if (n == 0) "None custom" else "$n custom"
    }
    StudioSection.SOUNDS -> {
        val n = state.mediaFiles.keys.count { it !in BOOT_KEYS }
        if (n == 0) "None set" else "$n set"
    }
    StudioSection.BOOT -> {
        val n = state.mediaFiles.keys.count { it in BOOT_KEYS }
        if (n == 0) "None set" else "$n of 2"
    }
    StudioSection.EXPORT_CHECK -> when {
        check.errors.isNotEmpty() -> check.errors.size.let { "$it error" + if (it == 1) "" else "s" }
        check.warnings.isNotEmpty() -> check.warnings.size.let { "$it warning" + if (it == 1) "" else "s" }
        else -> "Ready"
    }
}

/** Left rail: nine sections with a per-section meta line. Neutral chrome - never the theme accent. */
@Composable
fun SectionRail(
    selected: StudioSection,
    state: StudioState,
    check: ExportCheck,
    onSelect: (StudioSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier.verticalScroll(rememberScrollState()).padding(8.dp),
    ) {
        StudioSection.entries.forEach { section ->
            val isSelected = section == selected
            val flagged = section == StudioSection.EXPORT_CHECK && (check.errors.isNotEmpty() || check.warnings.isNotEmpty())
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(section) })
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    section.label,
                    fontSize = 14.sp,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    railMeta(section, state, check),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (flagged) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
