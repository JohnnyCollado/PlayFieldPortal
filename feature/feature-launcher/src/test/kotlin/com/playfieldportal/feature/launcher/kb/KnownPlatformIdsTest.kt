package com.playfieldportal.feature.launcher.kb

import com.playfieldportal.core.data.database.seeder.PlatformSeeder
import kotlin.test.Test
import kotlin.test.assertTrue

class KnownPlatformIdsTest {

    @Test fun `every seeded platform id is known`() {
        assertTrue(PlatformSeeder.DEFAULT_PLATFORMS.all { it.id in KnownPlatformIds.ALL })
    }
}
