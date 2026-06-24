package com.playfieldportal.core.domain.model

/**
 * A user-created folder/collection of games (e.g. "RPGs", "Currently Playing").
 *
 * Collections behave like Favorites but are user-defined and many-to-many: a game can
 * belong to multiple collections, and a collection can hold games from any platform.
 * Membership lives in a separate join table, so no game records are ever duplicated.
 */
data class GameCollection(
    val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0,
    // Number of games currently in the collection (derived; 0 when unknown).
    val gameCount: Int = 0,
)
