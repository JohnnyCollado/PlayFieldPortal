package com.playfieldportal.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** Item actions inside a payload share the row action's typeKey/arg encoding, so they cannot drift. */
class DetailActionTest {

    @Test
    fun `every notification action round-trips through a detail action`() {
        val actions = listOf(
            NotificationAction.None,
            NotificationAction.OpenCategory("games"),
            NotificationAction.OpenMemoryCard("psx"),
            NotificationAction.OpenGame(4207),
            NotificationAction.OpenSettingsScreen("settings_artwork"),
            NotificationAction.OpenUrl("https://example.invalid"),
            NotificationAction.ReviewShortcut("1a2b"),
        )
        for (action in actions) {
            assertEquals(action, action.toDetailAction().toNotificationAction())
        }
    }

    @Test
    fun `a shortcut review action round-trips through its stored pair`() {
        val action = NotificationAction.ReviewShortcut("1a2b")
        assertEquals(action, NotificationAction.decode(action.typeKey, action.arg))
    }
}
