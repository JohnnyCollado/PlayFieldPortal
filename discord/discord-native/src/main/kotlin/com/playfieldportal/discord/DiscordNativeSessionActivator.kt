package com.playfieldportal.discord

import com.playfieldportal.core.domain.discord.DiscordFriend
import com.playfieldportal.core.domain.discord.DiscordPresence
import com.playfieldportal.core.domain.discord.DiscordSessionActivator
import com.playfieldportal.core.domain.discord.DiscordUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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

    override suspend fun friends(): List<DiscordFriend> = withContext(Dispatchers.IO) {
        val json = DiscordNativeBridge.friendsJson()
        runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                DiscordFriend(
                    id = obj.optString("id"),
                    username = obj.optString("username"),
                    displayName = obj.optString("displayName"),
                    avatarUrl = obj.optString("avatarUrl"),
                    presence = DiscordPresence.fromStatusOrdinal(obj.optInt("status", 7)),
                    activity = composeActivity(
                        name = obj.optString("activityName"),
                        details = obj.optString("activityDetails"),
                        state = obj.optString("activityState"),
                    ),
                )
            }
        }.getOrDefault(emptyList())
    }

    override fun connectionStatus(): Int = DiscordNativeBridge.status()

    // "Details · State" is richest; fall back to details, then the app name. Null when not in-app.
    private fun composeActivity(name: String, details: String, state: String): String? = when {
        details.isNotBlank() && state.isNotBlank() -> "$details · $state"
        details.isNotBlank() -> details
        name.isNotBlank() -> name
        else -> null
    }
}
