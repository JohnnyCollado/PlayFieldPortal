package com.playfieldportal.discord

/**
 * Thin Kotlin entry point to the native Discord Social SDK bridge (`libdiscord_bridge.so`).
 *
 * M0 scope: only proves the native library loads and links against the vendored SDK. The real
 * client lifecycle, auth, presence, relationships, and voice APIs are added in later milestones
 * as this bridge grows a purpose-built surface backed by `DiscordBridge.cpp`.
 */
object DiscordNativeBridge {
    init {
        System.loadLibrary("discord_bridge")
    }

    /**
     * Liveness check: returns a static string from native code proving the `.so` loaded and the
     * Discord SDK symbols linked. Not part of the eventual public API.
     */
    external fun nativeSdkVersion(): String
}
