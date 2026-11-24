package com.playfieldportal.core.domain.text

import android.content.Context
import android.content.res.Resources

/**
 * Text for the user that is decided outside a composable — in a view model, a worker, a use case —
 * but must appear in the user's language.
 *
 * The deciding code names WHICH text (a string resource and its arguments); the screen resolves it
 * at the moment it is drawn, so a language change shows up on the next frame without the view
 * model having to re-emit. Being plain data, two equal texts compare equal, which is what view
 * model tests assert on.
 *
 * In a composable call `asString()` (core-ui). Elsewhere, with a [Context], call [resolve].
 */
sealed interface UiText {

    /** A string resource, formatted with [args]. An argument that is itself a [UiText] is resolved first. */
    data class Res(val id: Int, val args: List<Any> = emptyList()) : UiText

    /** A plurals resource: [count] picks the form, [args] fill the placeholders (the count by default). */
    data class Plural(val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText

    /** Text that is not translated: a game title, a file name, a value the user typed. */
    data class Raw(val value: String) : UiText

    /** Several texts shown together, each resolved on its own. */
    data class Joined(val parts: List<UiText>, val separator: String = "") : UiText

    fun resolve(resources: Resources): String = when (this) {
        is Res -> if (args.isEmpty()) resources.getString(id) else resources.getString(id, *resolved(args, resources))
        is Plural -> resources.getQuantityString(id, count, *resolved(args, resources))
        is Raw -> value
        is Joined -> parts.joinToString(separator) { it.resolve(resources) }
    }

    fun resolve(context: Context): String = resolve(context.resources)

    companion object {
        fun res(id: Int, vararg args: Any): UiText = Res(id, args.toList())

        fun plural(id: Int, count: Int, vararg args: Any): UiText =
            Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())

        fun raw(value: String): UiText = Raw(value)
    }
}

private fun resolved(args: List<Any>, resources: Resources): Array<Any> =
    args.map { if (it is UiText) it.resolve(resources) else it }.toTypedArray()
