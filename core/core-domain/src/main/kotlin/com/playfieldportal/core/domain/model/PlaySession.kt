package com.playfieldportal.core.domain.model

data class PlaySession(
    val id: Long = 0,
    val gameId: Long,
    val platformId: String,
    val launchedAt: Long,               // epoch millis
    val durationMillis: Long = 0,       // 0 if not tracked
)

data class RecentPlatform(
    val platform: Platform,
    val lastPlayedAt: Long,
    val recentGames: List<Game>,
)
