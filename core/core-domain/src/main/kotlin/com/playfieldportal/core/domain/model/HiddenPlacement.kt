package com.playfieldportal.core.domain.model

/**
 * Where an item can be hidden from. GLOBAL keeps the legacy "hide everywhere" behaviour.
 * PLATFORM hides a game from one Memory Card ([HiddenPlacement.locationId] = the platform id);
 * ANDROID_PLATFORM predates it and remains the location for the Android card's hides.
 */
enum class HideLocationType { GLOBAL, CATEGORY, COLLECTION, ANDROID_PLATFORM, PLATFORM, FAVORITES }

/**
 * A single "this item is hidden from this location" record. An item (an Android app or a
 * game/shortcut) can have several placements — e.g. hidden from one category and one collection but
 * still visible elsewhere. Labels are cached so the Hidden Items manager renders without extra
 * lookups. [locationId] is the category/collection id; it's empty for ANDROID_PLATFORM / FAVORITES.
 */
data class HiddenPlacement(
    val itemKey: String,
    val itemLabel: String,
    val locationType: HideLocationType,
    val locationId: String,
    val locationLabel: String,
    val createdAt: Long,
) {
    companion object {
        fun appKey(packageName: String) = "app:$packageName"
        fun gameKey(gameId: Long) = "game:$gameId"
    }
}
