package com.playfieldportal.feature.social.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.playfieldportal.core.domain.discord.DiscordFriend
import com.playfieldportal.core.domain.discord.DiscordPresence

/** Social ▸ Friends: the connected user's friends, online-first, with a presence dot. */
@Composable
fun FriendsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FriendsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val onlineCount = (state as? FriendsUiState.Loaded)?.friends?.count { it.presence.isOnline } ?: 0
            Text(
                if (onlineCount == 0) "Friends" else "Friends · $onlineCount online",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Button(onClick = onBack) { Text("Back") }
        }

        when (val s = state) {
            FriendsUiState.Loading -> Center { CircularProgressIndicator(color = Color.White) }
            FriendsUiState.Offline -> Center {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("You're offline", color = Color.White, fontSize = 18.sp)
                    Text(
                        "Reconnect to the internet to see your friends.",
                        color = Color(0xCCFFFFFF),
                        fontSize = 14.sp,
                    )
                    Button(onClick = viewModel::refresh) { Text("Retry") }
                }
            }
            is FriendsUiState.Loaded ->
                if (s.friends.isEmpty()) {
                    Center { Text("No friends to show", color = Color(0xCCFFFFFF), fontSize = 16.sp) }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(s.friends, key = { it.id }) { FriendRow(it) }
                    }
                }
        }
    }
}

@Composable
private fun FriendRow(friend: DiscordFriend) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            AsyncImage(
                model = friend.avatarUrl,
                contentDescription = null,
                modifier = Modifier.size(44.dp).clip(CircleShape),
            )
            Box(modifier = Modifier.size(12.dp).background(presenceColor(friend.presence), CircleShape))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(friend.label, color = Color.White, fontSize = 16.sp)
            val activity = friend.activity
            if (activity != null) {
                // Only ever their activity WITHIN Playfield Portal (SDK-scoped).
                Text("Playing $activity", color = Color(0xFF7FE0A0), fontSize = 12.sp, maxLines = 1)
            } else {
                Text(presenceLabel(friend.presence), color = Color(0xAAC8DAF2), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

private fun presenceColor(p: DiscordPresence): Color = when (p) {
    DiscordPresence.ONLINE, DiscordPresence.STREAMING -> Color(0xFF3BA55D)
    DiscordPresence.IDLE -> Color(0xFFFAA61A)
    DiscordPresence.DND -> Color(0xFFED4245)
    else -> Color(0xFF747F8D)
}

private fun presenceLabel(p: DiscordPresence): String = when (p) {
    DiscordPresence.ONLINE -> "Online"
    DiscordPresence.IDLE -> "Idle"
    DiscordPresence.DND -> "Do Not Disturb"
    DiscordPresence.STREAMING -> "Streaming"
    DiscordPresence.OFFLINE -> "Offline"
    DiscordPresence.UNKNOWN -> ""
}
