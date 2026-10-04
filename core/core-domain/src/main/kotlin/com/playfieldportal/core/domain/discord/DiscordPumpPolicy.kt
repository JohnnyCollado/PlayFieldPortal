package com.playfieldportal.core.domain.discord

/**
 * How often the native Discord pump (which runs SDK calls and delivers SDK callbacks) wakes.
 *
 * It used to wake every 10 ms forever once a session existed — 100 wakeups a second in the
 * background and with the screen off. Now it runs at [BUSY_MS] only while there is something to
 * deliver (a voice call, or a few seconds after any queued request so its callback lands promptly),
 * and otherwise idles at [idleIntervalMs]. A queued request wakes it immediately either way, so
 * presence updates and sign-in are not delayed by the idle beat.
 */
object DiscordPumpPolicy {
    /** In a call, or just after a request: the original fast cadence. */
    const val BUSY_MS = 10

    /** Nothing pending, launcher on screen: friend/presence updates still feel live. */
    const val FOREGROUND_IDLE_MS = 100

    /** Nothing pending, launcher in the background or the screen off. */
    const val BACKGROUND_IDLE_MS = 1_000

    fun idleIntervalMs(foreground: Boolean): Int = if (foreground) FOREGROUND_IDLE_MS else BACKGROUND_IDLE_MS
}
