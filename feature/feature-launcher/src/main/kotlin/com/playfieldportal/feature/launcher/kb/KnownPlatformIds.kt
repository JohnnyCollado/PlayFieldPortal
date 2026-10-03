package com.playfieldportal.feature.launcher.kb

import com.playfieldportal.core.data.database.seeder.PlatformSeeder
import com.playfieldportal.feature.launcher.platformAliases

/** Every platform id a knowledge file may name: the seeded platforms plus their aliases. */
object KnownPlatformIds {
    val ALL: Set<String> by lazy {
        PlatformSeeder.DEFAULT_PLATFORMS.map { it.id }.flatMap(::platformAliases).toSet()
    }
}
