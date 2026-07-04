package com.playfieldportal.discord

import com.playfieldportal.core.domain.discord.DiscordSessionActivator
import com.playfieldportal.core.domain.discord.DiscordUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Native-backed [DiscordSessionActivator]: hands the OAuth token to the SDK over the JNI bridge.
 * Native calls block on the SDK's pump thread, so they run on [Dispatchers.IO].
 */
@Singleton
class DiscordNativeSessionActivator @Inject constructor() : DiscordSessionActivator {

    override suspend fun activate(accessToken: String): Boolean = withContext(Dispatchers.IO) {
        DiscordNativeBridge.updateToken(accessToken)
    }

    override suspend fun deactivate() {
        withContext(Dispatchers.IO) { DiscordNativeBridge.disconnect() }
    }

    override suspend fun currentUser(): DiscordUser? = withContext(Dispatchers.IO) {
        val json = DiscordNativeBridge.currentUserJson()
        if (json.isBlank()) return@withContext null
        runCatching {
            val obj = JSONObject(json)
            DiscordUser(
                id = obj.optString("id"),
                username = obj.optString("username"),
                displayName = obj.optString("displayName"),
                avatarUrl = obj.optString("avatarUrl"),
            )
        }.getOrNull()
    }

    override fun connectionStatus(): Int = DiscordNativeBridge.status()
}
