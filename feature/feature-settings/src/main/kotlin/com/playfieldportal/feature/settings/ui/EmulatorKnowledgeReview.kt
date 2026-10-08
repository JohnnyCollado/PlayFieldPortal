package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.data.database.seeder.PlatformSeeder
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.IntentType
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbEmulator
import com.playfieldportal.core.domain.model.emulatorkb.FieldChange
import com.playfieldportal.core.domain.model.emulatorkb.ImportItem
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.core.ui.components.PfpCheckbox
import com.playfieldportal.feature.settings.viewmodel.KbImportReview

/** The warning badge colour Settings uses for "needs attention" states. */
internal val WarningAmber = Color(0xFFB7791F)

/** Same ink as the wizard's checkbox rows. */
private val CheckboxMark = Color(0xFF06224B)

private val ReviewHelperItems = listOf(
    ControllerPromptItem(GamepadAction.SELECT, "Toggle"),
    ControllerPromptItem(GamepadAction.BACK, "Cancel"),
)

internal val platformNames: Map<String, String> by lazy { PlatformSeeder.DEFAULT_PLATFORMS.associate { it.id to it.name } }

/**
 * The per-entry import review (AD-11), a sub-screen of the knowledge screen: what each entry in the
 * picked file would do, a checkbox where there is a choice, and Cancel / the confirm button. Back is a cancel.
 * Unsigned files are always flagged; nothing here stores anything until [onConfirm].
 */
@Composable
internal fun EmulatorKnowledgeReview(
    review: KbImportReview,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = review.plan.items
    val added = items.filterIsInstance<ImportItem.New>()
    val changed = items.filterIsInstance<ImportItem.Change>()
    val consoles = items.filterIsInstance<ImportItem.PlatformUpdate>()
    val blocked = items.filterIsInstance<ImportItem.Blocked>()
    val unchanged = items.count { it is ImportItem.Unchanged }

    SettingsScaffold(
        title = "Settings",
        subtitle = "Review ${review.displayName}",
        onBack = onCancel,
        modifier = modifier.fillMaxSize(),
        helperFooterItems = ReviewHelperItems,
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
            SettingsRow(
                label = "Not signed · from a file on this device",
                sublabel = "Import only files you trust: these tell PFP which app opens your games.",
                focusKey = "knowledge_review_unsigned",
                labelTrailing = { KnowledgeBadge("Unsigned", WarningAmber) },
            )
            if (added.isNotEmpty()) {
                SettingsGroup("New emulators")
                added.forEach { item ->
                    ReviewCheckRow(
                        label = "Add ${item.entry.name}",
                        sublabel = newEntrySummary(item.entry),
                        focusKey = "knowledge_review_${item.key}",
                        checked = item.selected,
                        onToggle = { onToggle(item.key) },
                    )
                }
            }
            if (changed.isNotEmpty()) {
                SettingsGroup("Already on this device")
                changed.forEach { item ->
                    ReviewCheckRow(
                        label = "Override ${item.entry.name}",
                        sublabel = diffText(item.diff),
                        focusKey = "knowledge_review_${item.key}",
                        checked = item.selected,
                        onToggle = { onToggle(item.key) },
                        badges = {
                            if (item.overridesOfficial) KnowledgeBadge("Overrides official", WarningAmber)
                            if (item.userEdited) KnowledgeBadge("Your edit", SettingsAccent.copy(alpha = 0.55f))
                        },
                    )
                }
            }
            if (consoles.isNotEmpty()) {
                SettingsGroup("Consoles")
                consoles.forEach { item ->
                    ReviewCheckRow(
                        label = "Update ${platformNames[item.id] ?: item.id}",
                        sublabel = "+ extensions: " + item.addedExtensions.joinToString(", "),
                        focusKey = "knowledge_review_${item.key}",
                        checked = item.selected,
                        onToggle = { onToggle(item.key) },
                    )
                }
            }
            if (blocked.isNotEmpty()) {
                SettingsGroup("Blocked")
                blocked.forEachIndexed { index, item ->
                    SettingsRow(
                        label = item.name ?: item.id ?: "Entry ${index + 1}",
                        sublabel = item.reason,
                        focusKey = "knowledge_review_blocked_$index",
                    )
                }
            }
            if (unchanged > 0) {
                SettingsRow(
                    label = "$unchanged already up to date",
                    focusKey = "knowledge_review_unchanged",
                    enabled = false,
                )
            }
            SettingsGroup("Finish")
            SettingsRow(label = "Cancel", focusKey = "knowledge_import_cancel", onClick = onCancel)
            SettingsRow(
                label = review.confirmLabel,
                focusKey = "knowledge_import_confirm",
                enabled = review.confirmEnabled,
                onClick = onConfirm,
            )
        }
    }
}

/** A checkbox row: the row's own SELECT or tap toggles it, as in the wizard's checkbox rows. */
@Composable
internal fun ReviewCheckRow(
    label: String,
    sublabel: String,
    focusKey: String,
    checked: Boolean,
    onToggle: () -> Unit,
    badges: (@Composable () -> Unit)? = null,
) {
    SettingsRow(
        label = label,
        sublabel = sublabel,
        focusKey = focusKey,
        leading = { PfpCheckbox(checked = checked, color = Color.White, markColor = CheckboxMark) },
        labelTrailing = badges?.let { { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { it() } } },
        onClick = onToggle,
    )
}

/** "org.ppsspp.ppsspp · PlayStation Portable · Opens the game file"; every package name is listed. */
internal fun newEntrySummary(entry: EmulatorKbEmulator): String = listOfNotNull(
    entry.packageNames.joinToString(", ").ifEmpty { null },
    entry.platformIds.joinToString(", ") { platformNames[it] ?: it }.ifEmpty { null },
    when (entry.launch.intentType) {
        IntentType.ACTION_VIEW -> "Opens the game file"
        IntentType.COMPONENT -> "Opens through its own activity"
        IntentType.SHORTCUT -> "Opens through a shortcut"
        IntentType.CUSTOM_COMMAND -> "Opens with a custom command"
    },
).joinToString(" · ")

/** "- field: before" / "+ field: after" for every changed field; a review hides nothing. */
internal fun diffText(diff: List<FieldChange>): String =
    diff.flatMap { listOf("- ${it.field}: ${readable(it.field, it.before)}", "+ ${it.field}: ${readable(it.field, it.after)}") }
        .joinToString("\n")

private val IntentTypeText = mapOf(
    "ACTION_VIEW" to "Opens the ROM file",
    "COMPONENT" to "Opens a specific screen",
    "SHORTCUT" to "Opens through a shortcut",
)

/** A diff value in words: intent types say what they do and console ids read as console names. */
private fun readable(field: String, value: String): String = when (field) {
    "Intent type" -> IntentTypeText[value] ?: value
    "Platforms" -> value.split(", ").joinToString(", ") { platformNames[it] ?: it }
    "Per-package launch" -> IntentTypeText.entries.fold(value) { text, (type, words) -> text.replace("type $type", "type $words") }
    else -> value
}
