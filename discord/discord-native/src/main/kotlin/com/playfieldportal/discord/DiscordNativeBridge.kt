package com.playfieldportal.discord

import android.app.Activity
import com.discord.socialsdk.DiscordSocialSdkInit
import com.playfieldportal.core.domain.discord.DiscordConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kotlin entry point to the native Discord Social SDK bridge (`libdiscord_bridge.so`).
 *
 * All calls are forwarded to a single pump thread inside native code; these wrappers only guard
 * one-time library load / client init. No tokens are logged here or natively.
 */
object DiscordNativeBridge {
    private val libraryLoaded = AtomicBoolean(false)
    private val clientStarted = AtomicBoolean(false)

    private fun ensureLibraryLoaded() {
        if (libraryLoaded.compareAndSet(false, true)) System.loadLibrary("discord_bridge")
    }

    /**
     * Must be called from the main Activity's `onCreate`, before any session use: the SDK needs the
     * Android activity/context for networking, the auth Custom Tab, and audio routing.
     */
    fun attachActivity(activity: Activity) {
        ensureLibraryLoaded()
        DiscordSocialSdkInit.setEngineActivity(activity)
    }

    /** Create the client and start the callback pump exactly once. */
    fun ensureInitialized() {
        ensureLibraryLoaded()
        if (clientStarted.compareAndSet(false, true)) {
            nativeInit(DiscordConfig.APPLICATION_ID.toLong())
        }
    }

    /** Push a bearer token into the SDK and connect. Blocking; call off the main thread. */
    fun updateToken(accessToken: String): Boolean {
        ensureInitialized()
        return nativeUpdateToken(accessToken)
    }

    fun disconnect() {
        if (clientStarted.get()) nativeDisconnect()
    }

    /** Current status ordinal (mirrors `discordpp::Client::Status`; 0 = Disconnected). */
    fun status(): Int = if (clientStarted.get()) nativeGetStatus() else 0

    /** Current user as a JSON string, or "" until the session is Ready. Call off the main thread. */
    fun currentUserJson(): String = if (clientStarted.get()) nativeGetCurrentUserJson() else ""

    /** Friends as a JSON array string, or "[]" until Ready. Call off the main thread. */
    fun friendsJson(): String = if (clientStarted.get()) nativeGetFriendsJson() else "[]"

    /** Broadcast app-scoped rich presence (what friends see as "Playing …"). Call off the main thread. */
    fun setActivity(name: String, details: String) {
        if (clientStarted.get()) nativeSetActivity(name, details)
    }

    /** Clear any broadcast presence. Call off the main thread. */
    fun clearActivity() {
        if (clientStarted.get()) nativeClearActivity()
    }

    fun shutdown() {
        if (clientStarted.compareAndSet(true, false)) nativeShutdown()
    }

    private external fun nativeInit(applicationId: Long)
    private external fun nativeUpdateToken(token: String): Boolean
    private external fun nativeDisconnect()
    private external fun nativeGetStatus(): Int
    private external fun nativeGetCurrentUserJson(): String
    private external fun nativeGetFriendsJson(): String
    private external fun nativeSetActivity(name: String, details: String)
    private external fun nativeClearActivity()
    private external fun nativeShutdown()
}
