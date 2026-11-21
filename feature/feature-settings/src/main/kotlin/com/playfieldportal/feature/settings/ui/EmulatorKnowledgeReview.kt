package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
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
import com.playfieldportal.core.ui.text.asString
import com.playfieldportal.feature.settings.R
import com.playfieldportal.feature.settings.viewmodel.KbImportReview

/** The warning badge colour Settings uses for "needs attention" states. */
internal val WarningAmber = Color(0xFFB7791F)

/** Same ink as the wizard's checkbox rows. */
private val CheckboxMark = Color(0xFF06224B)

/** The footer prompts shared by the import review and the export picker. */
@Composable
internal fun knowledgeHelperItems() = listOf(
    ControllerPromptItem(GamepadAction.SELECT, stringResource(R.string.lib_kb_prompt_toggle)),
    ControllerPromptItem(GamepadAction.BACK, stringResource(R.string.lib_kb_prompt_cancel)),
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
        title = stringResource(R.string.settings_title),
        subtitle = stringResource(R.string.lib_kb_review_title, review.displayName),
        onBack = onCancel,
        modifier = modifier.fillMaxSize(),
        helperFooterItems = knowledgeHelperItems(),
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
            SettingsRow(
                label = stringResource(R.string.lib_kb_review_unsigned),
                sublabel = stringResource(R.string.lib_kb_review_unsigned_sub),
                focusKey = "knowledge_review_unsigned",
                labelTrailing = { KnowledgeBadge(stringResource(R.string.lib_kb_badge_unsigned), WarningAmber) },
            )
            if (added.isNotEmpty()) {
                SettingsGroup(stringResource(R.string.lib_kb_group_new))
                added.forEach { item ->
                    ReviewCheckRow(
                        label = stringResource(R.string.lib_kb_review_add, item.entry.name),
                        sublabel = newEntrySummary(item.entry),
                        focusKey = "knowledge_review_${item.key}",
                        checked = item.selected,
                        onToggle = { onToggle(item.key) },
                    )
                }
            }
            if (changed.isNotEmpty()) {
                SettingsGroup(stringResource(R.string.lib_kb_group_existing))
                changed.forEach { item ->
                    ReviewCheckRow(
                        label = stringResource(R.string.lib_kb_review_override, item.entry.name),
                        sublabel = diffText(item.diff),
                        focusKey = "knowledge_review_${item.key}",
                        checked = item.selected,
                        onToggle = { onToggle(item.key) },
                        badges = {
                            if (item.overridesOfficial) KnowledgeBadge(stringResource(R.string.lib_kb_badge_overrides_official), WarningAmber)
                            if (item.userEdited) KnowledgeBadge(stringResource(R.string.lib_kb_badge_your_edit), SettingsAccent.copy(alpha = 0.55f))
                        },
                    )
                }
            }
            if (consoles.isNotEmpty()) {
                SettingsGroup(stringResource(R.string.lib_kb_group_consoles))
                consoles.forEach { item ->
                    ReviewCheckRow(
                        label = stringResource(R.string.lib_kb_review_update, platformNames[item.id] ?: item.id),
                        sublabel = stringResource(R.string.lib_kb_review_extensions, item.addedExtensions.joinToString(", ")),
                        focusKey = "knowledge_review_${item.key}",
                        checked = item.selected,
                        onToggle = { onToggle(item.key) },
                    )
                }
            }
            if (blocked.isNotEmpty()) {
                SettingsGroup(stringResource(R.string.lib_kb_group_blocked))
                blocked.forEachIndexed { index, item ->
                    SettingsRow(
                        label = item.name ?: item.id ?: stringResource(R.string.lib_kb_review_entry, index + 1),
                        sublabel = item.reason,
                        focusKey = "knowledge_review_blocked_$index",
                    )
                }
            }
            if (unchanged > 0) {
                SettingsRow(
                    label = stringResource(R.string.lib_kb_review_unchanged, unchanged),
                    focusKey = "knowledge_review_unchanged",
                    enabled = false,
                )
            }
            SettingsGroup(stringResource(R.string.lib_kb_group_finish))
            SettingsRow(label = stringResource(R.string.lib_kb_cancel), focusKey = "knowledge_import_cancel", onClick = onCancel)
            SettingsRow(
                label = review.confirmLabel.asString(),
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
@Composable
internal fun newEntrySummary(entry: EmulatorKbEmulator): String = listOfNotNull(
    entry.packageNames.joinToString(", ").ifEmpty { null },
    entry.platformIds.joinToString(", ") { platformNames[it] ?: it }.ifEmpty { null },
    stringResource(
        when (entry.launch.intentType) {
            IntentType.ACTION_VIEW -> R.string.lib_kb_opens_game_file
            IntentType.COMPONENT -> R.string.lib_kb_opens_own_activity
            IntentType.SHORTCUT -> R.string.lib_kb_opens_shortcut
            IntentType.CUSTOM_COMMAND -> R.string.lib_kb_opens_custom_command
        },
    ),
).joinToString(" · ")

/** "- field: before" / "+ field: after" for every changed field; a review hides nothing. */
@Composable
internal fun diffText(diff: List<FieldChange>): String =
    diff.flatMap { listOf("- ${it.field}: ${readable(it.field, it.before)}", "+ ${it.field}: ${readable(it.field, it.after)}") }
        .joinToString("\n")

private val IntentTypeText = mapOf(
    "ACTION_VIEW" to R.string.lib_kb_intent_view,
    "COMPONENT" to R.string.lib_kb_intent_component,
    "SHORTCUT" to R.string.lib_kb_intent_shortcut,
)

/** A diff value in words: intent types say what they do and console ids read as console names. */
@Composable
private fun readable(field: String, value: String): String = when (field) {
    "Intent type" -> IntentTypeText[value]?.let { stringResource(it) } ?: value
    "Platforms" -> value.split(", ").joinToString(", ") { platformNames[it] ?: it }
    "Per-package launch" -> IntentTypeText.entries.fold(value) { text, (type, words) -> text.replace("type $type", "type ${stringResource(words)}") }
    else -> value
}
