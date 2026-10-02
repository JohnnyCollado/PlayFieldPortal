package com.playfieldportal.core.ui.components

import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.sound.MenuSoundSink

/** Which cue activating a [PspMenuRow] plays: none, [SELECT] to open a list, [CONFIRM] to commit. */
enum class PspMenuCue { NONE, SELECT, CONFIRM }

/** What [PspMenuNav] decided for one press; the host applies it to its own state. */
sealed interface PspMenuOutcome {
    /** The cursor moved to [index]. */
    data class Moved(val index: Int) : PspMenuOutcome

    /** The focused row was activated; what that means is the host's. */
    data object Activate : PspMenuOutcome

    /** Back from below the root: climb one level, cursor where it was left. */
    data object Up : PspMenuOutcome

    /** Close the whole menu. */
    data object Close : PspMenuOutcome

    /** Nothing happened (an edge, an empty list, a press the menu does not use). */
    data object Ignored : PspMenuOutcome
}

/**
 * The rules every PSP-panel menu shares, for a host's `handleGamepadAction`, so menus clamp, climb,
 * close and sound alike wherever they appear: the cursor does not wrap; Back climbs one level and
 * closes at the root; Triangle (OPEN_CONTEXT_MENU) closes from any depth; SCROLL plays only when the
 * cursor actually moves; opening plays SELECT, committing plays CONFIRM, leaving plays BACK.
 */
object PspMenuNav {

    /**
     * The outcome of [action] with the cursor on [index] of [rowCount] rows, [depth] levels below the
     * root. Pure — no sound; see [handle].
     */
    fun resolve(action: GamepadAction, index: Int, rowCount: Int, depth: Int): PspMenuOutcome = when (action) {
        GamepadAction.NAVIGATE_UP -> moved(index, index - 1, rowCount)
        GamepadAction.NAVIGATE_DOWN -> moved(index, index + 1, rowCount)
        GamepadAction.SELECT -> if (rowCount > 0) PspMenuOutcome.Activate else PspMenuOutcome.Ignored
        GamepadAction.BACK -> if (depth > 0) PspMenuOutcome.Up else PspMenuOutcome.Close
        GamepadAction.OPEN_CONTEXT_MENU -> PspMenuOutcome.Close
        else -> PspMenuOutcome.Ignored
    }

    /** [resolve], then plays the matching cue through [sounds]. [cue] is the focused row's. */
    fun handle(
        action: GamepadAction,
        index: Int,
        rowCount: Int,
        depth: Int,
        cue: PspMenuCue,
        sounds: MenuSoundSink,
    ): PspMenuOutcome {
        val outcome = resolve(action, index, rowCount, depth)
        when (outcome) {
            is PspMenuOutcome.Moved -> sounds.play(MenuSound.SCROLL)
            PspMenuOutcome.Activate -> when (cue) {
                PspMenuCue.SELECT -> sounds.play(MenuSound.SELECT)
                PspMenuCue.CONFIRM -> sounds.play(MenuSound.CONFIRM)
                PspMenuCue.NONE -> Unit
            }
            PspMenuOutcome.Up, PspMenuOutcome.Close -> sounds.play(MenuSound.BACK)
            PspMenuOutcome.Ignored -> Unit
        }
        return outcome
    }

    private fun moved(from: Int, to: Int, rowCount: Int): PspMenuOutcome {
        val clamped = to.coerceIn(0, (rowCount - 1).coerceAtLeast(0))
        return if (rowCount > 0 && clamped != from) PspMenuOutcome.Moved(clamped) else PspMenuOutcome.Ignored
    }
}
