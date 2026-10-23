package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.GamepadAction

/** What a pad button does while the Discord QR login overlay is up. */
internal enum class DiscordLoginInput { CLOSE, CONFIRM, IGNORE }

/**
 * Back closes the overlay (which stops its login poll); Confirm is handed to the overlay as Try
 * again or Copy code, the same as its tap targets. Before, only Back did anything, so those two
 * were touch-only (survey P1, docs/audits/codebase-survey-2026-10-22.md).
 */
internal fun discordLoginInput(action: GamepadAction): DiscordLoginInput = when (action) {
    GamepadAction.BACK -> DiscordLoginInput.CLOSE
    GamepadAction.SELECT -> DiscordLoginInput.CONFIRM
    else -> DiscordLoginInput.IGNORE
}
