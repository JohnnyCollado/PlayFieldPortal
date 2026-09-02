package com.playfieldportal.core.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * What a controller input *does*, independent of which button performs it.
 *
 * Every constant names an intent, never a silkscreened letter: which physical
 * button triggers an action is the user's choice (Settings ▸ Controller), and
 * the same letter sits in different places on different pads. Screens dispatch
 * on these; footers resolve the current button for one via
 * [GamepadMappings.iconFor].
 */
@Serializable(with = GamepadActionSerializer::class)
enum class GamepadAction {
    NAVIGATE_UP,
    NAVIGATE_DOWN,
    NAVIGATE_LEFT,
    NAVIGATE_RIGHT,
    SELECT,
    BACK,
    /** Open the context / options menu — also what a touch long-press raises. */
    OPEN_CONTEXT_MENU,
    /** Cycle the sort order of the current list. */
    CHANGE_SORT,
    PREV_CATEGORY,
    NEXT_CATEGORY,
    HOME,
}

/** True for the four directional navigation actions (D-pad / stick movement). */
val GamepadAction.isDirectional: Boolean
    get() = this == GamepadAction.NAVIGATE_UP ||
        this == GamepadAction.NAVIGATE_DOWN ||
        this == GamepadAction.NAVIGATE_LEFT ||
        this == GamepadAction.NAVIGATE_RIGHT

// ── Persisted-name migration ─────────────────────────────────────────────────
//
// Mappings persist as JSON holding enum names, so renaming a constant would
// make every saved table unparseable and silently reset the user's layout to
// defaults. These aliases keep older saves readable.
//
// The retired names came in pairs that always meant one thing:
//   BUTTON_Y + LONG_PRESS       → OPEN_CONTEXT_MENU
//   BUTTON_X + OPEN_TASK_TRAY   → CHANGE_SORT
// (OPEN_TASK_TRAY outlived the task tray itself, which was removed; every
// screen had already repurposed it to sort.)
private val LEGACY_ACTION_NAMES = mapOf(
    "BUTTON_Y" to GamepadAction.OPEN_CONTEXT_MENU,
    "LONG_PRESS" to GamepadAction.OPEN_CONTEXT_MENU,
    "BUTTON_X" to GamepadAction.CHANGE_SORT,
    "OPEN_TASK_TRAY" to GamepadAction.CHANGE_SORT,
)

/**
 * Resolves a persisted action name — current or legacy — to its action, or
 * `null` if the name belongs to neither vocabulary.
 */
fun gamepadActionFromPersistedName(raw: String): GamepadAction? =
    GamepadAction.entries.firstOrNull { it.name == raw } ?: LEGACY_ACTION_NAMES[raw]

/** Writes current names, reads current *and* legacy ones. */
object GamepadActionSerializer : KSerializer<GamepadAction> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.playfieldportal.GamepadAction", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: GamepadAction) =
        encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): GamepadAction {
        val raw = decoder.decodeString()
        return gamepadActionFromPersistedName(raw)
            ?: throw SerializationException("Unknown GamepadAction '$raw'")
    }
}
