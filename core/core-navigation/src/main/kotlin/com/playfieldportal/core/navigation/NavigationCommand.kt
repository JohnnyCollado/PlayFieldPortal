package com.playfieldportal.core.navigation

/**
 * Navigation commands arriving from the input mapper (spec §18). Directional intents stay
 * directional — the engine never sees abstract next/previous (spec §2.3).
 */
sealed class NavigationCommand {
    data class Direction(val direction: NavigationDirection) : NavigationCommand()
    data object Confirm : NavigationCommand()
    data object Back : NavigationCommand()
}
