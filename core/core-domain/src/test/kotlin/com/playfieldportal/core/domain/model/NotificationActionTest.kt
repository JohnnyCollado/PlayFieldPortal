package com.playfieldportal.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The action column is the one part of a notification row that a future build can widen, so the
 * rule that matters here is what an *older* build does with a value it has never heard of: it has
 * to degrade, not throw. A crash on read would make one newer row poison the whole panel.
 */
class NotificationActionTest {

    @Test
    fun `every action round-trips through its stored type and arg`() {
        val actions = listOf(
            NotificationAction.None,
            NotificationAction.OpenCategory("games"),
            NotificationAction.OpenMemoryCard("psx"),
            NotificationAction.OpenGame(4207),
            NotificationAction.OpenSettingsScreen("settings_artwork"),
            NotificationAction.OpenUrl("https://example.invalid/feed.xml"),
        )
        for (action in actions) {
            assertEquals(
                action,
                NotificationAction.decode(action.typeKey, action.arg),
                "${action.typeKey} did not survive a round-trip",
            )
        }
    }

    @Test
    fun `an unknown action type degrades to None rather than throwing`() {
        assertEquals(
            NotificationAction.None,
            NotificationAction.decode("open_holodeck", "arg"),
        )
    }

    @Test
    fun `a null type and a null arg both degrade to None`() {
        assertEquals(NotificationAction.None, NotificationAction.decode(null, null))
        assertEquals(NotificationAction.None, NotificationAction.decode("open_game", null))
    }

    @Test
    fun `a game action with a non-numeric arg degrades instead of crashing on parse`() {
        assertEquals(
            NotificationAction.None,
            NotificationAction.decode(NotificationAction.TYPE_GAME, "not-a-row-id"),
        )
    }

    @Test
    fun `unknown kinds and severities read back as their safe defaults`() {
        assertEquals(NotificationKind.SYSTEM, NotificationKind.fromName("HOLODECK"))
        assertEquals(NotificationKind.SYSTEM, NotificationKind.fromName(null))
        assertEquals(NotificationKind.ARTWORK, NotificationKind.fromName("ARTWORK"))
        assertEquals(NotificationSeverity.INFO, NotificationSeverity.fromName("CATASTROPHE"))
        assertEquals(NotificationSeverity.ERROR, NotificationSeverity.fromName("ERROR"))
    }
}
