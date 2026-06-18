package com.playfieldportal.core.data.database

import androidx.room.TypeConverter

// Room cannot store complex types natively — these converters handle
// the few cases where a simple primitive is not enough.
// We deliberately keep this minimal — most complex types are stored
// as JSON strings directly in entity fields (e.g. FilterRules).
class PFPTypeConverters {

    // Long? ↔ Long — Room handles nullable Long natively as NULL in SQLite,
    // so no converter needed. Listed here as a reminder that it's intentional.

    // List<String> stored as comma-separated in PlatformEntity.romExtensions.
    // Conversion happens in the entity mapper (toDomain/toEntity), not here,
    // to keep the DB column human-readable in SQLite browsers.

    // Future converters go here as schema evolves — e.g. if we add
    // a List<String> column directly on an entity rather than joining.
    @TypeConverter
    fun fromStringList(value: String?): List<String> =
        value?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    @TypeConverter
    fun toStringList(list: List<String>): String =
        list.joinToString(",")
}
