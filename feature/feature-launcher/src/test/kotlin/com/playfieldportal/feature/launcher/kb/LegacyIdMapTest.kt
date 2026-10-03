package com.playfieldportal.feature.launcher.kb

import com.playfieldportal.core.domain.model.emulatorkb.EffectiveKbEmulator
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbEmulator
import com.playfieldportal.core.domain.model.emulatorkb.KbSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegacyIdMapTest {

    private fun entry(
        id: String,
        vararg packages: String,
        legacy: List<String> = emptyList(),
        source: KbSource = KbSource.BuiltIn,
    ) = EffectiveKbEmulator(
        EmulatorKbEmulator(id = id, name = id, packageNames = packages.toList(), legacyIds = legacy),
        source,
        null,
    )

    @Test fun `nothing is mapped while no package of the entry is installed`() {
        val map = EmulatorKnowledgeRefresher.legacyIdMap(
            listOf(entry("ppsspp", "org.ppsspp.ppsspp", "org.ppsspp.ppssppgold", legacy = listOf("ppsspp_gold"))),
            installedPackages = emptySet(),
        )

        assertTrue(map.isEmpty())
    }

    @Test fun `an entry id and its legacy ids map to the installed package`() {
        val map = EmulatorKnowledgeRefresher.legacyIdMap(
            listOf(entry("ppsspp", "org.ppsspp.ppsspp", "org.ppsspp.ppssppgold", legacy = listOf("ppsspp_gold"))),
            installedPackages = setOf("org.ppsspp.ppssppgold"),
        )

        assertEquals(
            mapOf("ppsspp" to "auto_org_ppsspp_ppssppgold", "ppsspp_gold" to "auto_org_ppsspp_ppssppgold"),
            map,
        )
    }

    @Test fun `an unrelated installed package maps nothing`() {
        val map = EmulatorKnowledgeRefresher.legacyIdMap(
            listOf(entry("duckstation", "com.github.stenzek.duckstation")),
            installedPackages = setOf("org.other"),
        )

        assertTrue(map.isEmpty())
    }

    @Test fun `built-in and official entries map but user entries never do`() {
        val map = EmulatorKnowledgeRefresher.legacyIdMap(
            listOf(
                entry("duckstation", "com.github.stenzek.duckstation", source = KbSource.BuiltIn),
                entry("flycast", "com.flycast.emulator", source = KbSource.Official),
                entry("citra", "com.evil.app", legacy = listOf("winlator"), source = KbSource.User("file1")),
            ),
            installedPackages = setOf("com.github.stenzek.duckstation", "com.flycast.emulator", "com.evil.app"),
        )

        assertEquals(setOf("duckstation", "flycast"), map.keys)
    }
}
