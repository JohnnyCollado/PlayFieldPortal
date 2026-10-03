package com.playfieldportal.studio.ui.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.studio.CheckSeverity
import com.playfieldportal.studio.ExportCheck
import com.playfieldportal.themekit.PfpThemeManifest
import com.playfieldportal.themekit.UpgradeReport

/** "Format: schema N → 4" (or "new theme" when nothing was opened). Pure. */
fun exportFormatLine(schemaVersion: Int?): String =
    "Format: ${schemaVersion?.let { "schema $it" } ?: "new theme"} → ${PfpThemeManifest.SCHEMA_VERSION}"

/** The non-empty groups of an upgrade report, titled, in reading order. Pure. */
fun upgradeSections(report: UpgradeReport?): List<Pair<String, List<String>>> {
    if (report == null) return emptyList()
    return listOf(
        "Kept" to report.kept,
        "Added" to report.added,
        "Repaired" to report.repaired,
        "Can't recover" to report.cantRecover,
    ).filter { it.second.isNotEmpty() }
}

private fun glyph(severity: CheckSeverity): String = when (severity) {
    CheckSeverity.OK -> "✓"
    CheckSeverity.INFO -> "ℹ"
    CheckSeverity.WARNING -> "⚠"
    CheckSeverity.ERROR -> "✕"
}

@Composable
private fun severityColor(severity: CheckSeverity): Color = when (severity) {
    CheckSeverity.ERROR -> MaterialTheme.colorScheme.error
    CheckSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun ExportCheckSection(
    check: ExportCheck,
    schemaVersion: Int?,
    exportEnabled: Boolean,
    onExport: () -> Unit,
) {
    SectionColumn {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            check.items.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(glyph(item.severity), fontSize = 13.sp, color = severityColor(item.severity), modifier = Modifier.width(16.dp))
                    Text(item.message, fontSize = 13.sp, color = severityColor(item.severity))
                }
            }
        }

        val sections = upgradeSections(check.upgrade)
        if (sections.isNotEmpty()) {
            HorizontalDivider()
            SectionHeading("What saving does")
            sections.forEach { (title, lines) ->
                Text(title, fontSize = 12.sp, style = MaterialTheme.typography.labelLarge)
                lines.forEach { MutedText("•  $it", 12) }
            }
        }

        HorizontalDivider()
        MutedText(exportFormatLine(schemaVersion))
        MutedText("A preview image of the finished theme is rendered when you export and shows as its thumbnail in the launcher's theme gallery.", 11)
        Button(onClick = onExport, enabled = exportEnabled, modifier = Modifier.padding(top = 4.dp)) {
            Text("Export .pfptheme…")
        }
        if (check.errors.isNotEmpty()) HintText("Fix the items marked ✕ before exporting.")
    }
}
