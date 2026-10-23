package com.playfieldportal.feature.settings.ui

import com.playfieldportal.core.ui.components.PfpChoiceOption
import com.playfieldportal.core.ui.components.PfpModalSpec

/** One choice in a default-player picker: the stored value (a package, a sentinel, or null) and its label. */
internal data class PlayerOption(val value: String?, val label: String)

/**
 * A default-player picker (Music, Video) as the shared Choice modal. It opens on [current], so
 * Confirm right away keeps the player you have. These were Material dialogs with no controller
 * routing (survey P6, docs/audits/codebase-survey-2026-10-22.md); the shared modal works by pad and
 * touch alike. [emptyNote] is shown when no other player app is installed.
 */
internal fun playerPickerSpec(
    title: String,
    options: List<PlayerOption>,
    current: String?,
    emptyNote: String?,
    onChoose: (String?) -> Unit,
    onClose: () -> Unit,
): PfpModalSpec.Choice = PfpModalSpec.Choice(
    key = "player_picker:$title",
    title = title,
    message = emptyNote.orEmpty(),
    options = options.map { PfpChoiceOption(it.label) },
    confirmLabel = "Use This Player",
    cancelLabel = "Close",
    initialSelected = options.indexOfFirst { it.value == current }.coerceAtLeast(0),
    onConfirm = { index -> options.getOrNull(index)?.let { onChoose(it.value) } },
    onCancel = onClose,
)
