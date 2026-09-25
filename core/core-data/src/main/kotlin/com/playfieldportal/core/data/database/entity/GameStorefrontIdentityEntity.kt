package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One game's confirmed identity on one storefront (C23 T6, Phases 1 and 11) — the permanent
 * relationship a title search exists to discover and then never be needed for again.
 *
 * **Why this is not `games.storefront` / `games.storefront_game_id`.** Those two columns hold the
 * INSTALLATION identity: where this particular entry was imported from, captured from a launch
 * intent, and what `getByStorefront` deduplicates imports against. A resolved identity is a
 * different fact — "the game in this row is the one Steam calls 620" — and is true of a Winlator
 * shortcut, an ES-DE entry or a hand-added game that no launcher ever reported. Writing a resolved
 * id into the import columns would make a future Steam import dedupe against a game it never
 * installed. Phase 11 states the separation; this table is it.
 *
 * **Why one row per (game, store) and not one column set per store on `games`.** A single game may
 * hold an identity on every store at once, and must not be split into three copies of itself. A
 * composite-key child table says exactly that and costs one migration instead of one per store
 * added.
 *
 * **Why the Epic columns exist with no Epic provider.** Epic's catalog is addressed by a triple
 * (namespace, catalogItemId, appName) where Steam needs one appid. Naming them now costs three
 * nullable columns; discovering them later costs a migration on a table users already have rows
 * in. Steam leaves all three null.
 *
 * Cascade-deleted with its game. Nothing here expires: a search-cache entry going stale must never
 * be able to cost a game its identity (Phase 14).
 */
@Entity(
    tableName = "game_storefront_identities",
    primaryKeys = ["game_id", "store"],
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["game_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["store", "store_id"])],
)
data class GameStorefrontIdentityEntity(

    @ColumnInfo(name = "game_id")
    val gameId: Long,

    /** `Storefront.key` — STEAM / GOG / EPIC. The same vocabulary `games.storefront` uses. */
    val store: String,

    /** The store's own primary key: a Steam appid, a GOG product id. Unique within [store] only. */
    @ColumnInfo(name = "store_id")
    val storeId: String,

    /** Epic only. Null for every Steam and GOG row. */
    val namespace: String? = null,

    @ColumnInfo(name = "catalog_item_id")
    val catalogItemId: String? = null,

    @ColumnInfo(name = "app_name")
    val appName: String? = null,

    /** `MatchConfidence.name` as it stood when the link was made — why this row is trusted. */
    val confidence: String,

    /**
     * True when a human picked this match. It outranks anything an automatic pass derives: a later
     * resolver run may refresh metadata through this id but must never replace the id itself.
     */
    @ColumnInfo(name = "user_confirmed")
    val userConfirmed: Boolean = false,

    /** The store's title at the moment of linking — what a Rematch screen shows the user. */
    @ColumnInfo(name = "resolved_title")
    val resolvedTitle: String? = null,

    @ColumnInfo(name = "linked_at")
    val linkedAt: Long,

    /** When the id was last confirmed to still name a real game. Null = never re-checked. */
    @ColumnInfo(name = "last_verified_at")
    val lastVerifiedAt: Long? = null,
)
