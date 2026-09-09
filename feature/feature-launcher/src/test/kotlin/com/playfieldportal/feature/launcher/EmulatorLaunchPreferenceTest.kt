package com.playfieldportal.feature.launcher

import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.IntentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A freshly detected console must default to a standalone emulator when one is installed;
 * RetroArch cores stay selectable but never win the automatic pick.
 */
class EmulatorLaunchPreferenceTest {

    private fun profile(
        id: String,
        packageName: String,
        autoSource: String? = "auto-detected",
    ) = EmulatorProfile(
        id = id,
        name = id,
        packageName = packageName,
        intentType = IntentType.ACTION_VIEW,
        supportedPlatformIds = listOf("snes"),
        autoSource = autoSource,
    )

    @Test
    fun `retroarch profiles are identified by package and auto source`() {
        assertTrue(profile("ra", "com.retroarch", "retroarch-core").isRetroArchProfile())
        assertTrue(profile("ra64", "com.retroarch.aarch64", "retroarch-core").isRetroArchProfile())
        // Package alone is enough, even for a hand-made custom profile.
        assertTrue(profile("custom", "com.retroarch", autoSource = null).isRetroArchProfile())
        assertFalse(profile("snes9x_ex", "com.explusalpha.Snes9xPlus").isRetroArchProfile())
    }

    @Test
    fun `standalone wins the automatic pick over a retroarch core`() {
        // RetroArch listed first on purpose — ordering must not depend on input order.
        val ordered = listOf(
            profile("ra_snes9x", "com.retroarch", "retroarch-core"),
            profile("snes9x_ex", "com.explusalpha.Snes9xPlus"),
        ).byLaunchPreference()

        assertEquals("snes9x_ex", ordered.first().id, "A standalone must be the default pick")
        assertEquals(2, ordered.size, "RetroArch must remain selectable, not be filtered out")
    }

    @Test
    fun `ordering is stable within each tier`() {
        val ordered = listOf(
            profile("ra_a", "com.retroarch", "retroarch-core"),
            profile("standalone_a", "com.a"),
            profile("ra_b", "com.retroarch", "retroarch-core"),
            profile("standalone_b", "com.b"),
        ).byLaunchPreference()

        assertEquals(
            listOf("standalone_a", "standalone_b", "ra_a", "ra_b"),
            ordered.map { it.id },
        )
    }

    @Test
    fun `retroarch-only platform still resolves to retroarch`() {
        val ordered = listOf(profile("ra_snes9x", "com.retroarch", "retroarch-core")).byLaunchPreference()
        assertEquals("ra_snes9x", ordered.first().id)
    }

    // ── stabilizeCore: a console's remembered RetroArch core stays the automatic pick ──────

    @Test
    fun `remembered core moves to the front of the retroarch tier`() {
        val pool = listOf(
            profile("ra_mgba", "com.retroarch", "retroarch-core"),
            profile("ra_gambatte", "com.retroarch", "retroarch-core"),
            profile("snes9x_ex", "com.explusalpha.Snes9xPlus"),
        )

        val stabilized = pool.stabilizeCore("ra_gambatte")

        // A standalone still wins the automatic pick; among RetroArch cores the remembered one
        // leads, so installing a new core can never silently swap the console's core.
        assertEquals(
            listOf("snes9x_ex", "ra_gambatte", "ra_mgba"),
            stabilized.map { it.id },
        )
    }

    @Test
    fun `remembered core keeps its place among retroarch cores with no standalone`() {
        val pool = listOf(
            profile("ra_mgba", "com.retroarch", "retroarch-core"),
            profile("ra_gambatte", "com.retroarch", "retroarch-core"),
        )

        assertEquals(
            listOf("ra_gambatte", "ra_mgba"),
            pool.stabilizeCore("ra_gambatte").map { it.id },
        )
    }

    @Test
    fun `remembered core missing from the pool is ignored`() {
        // ra_gambatte left the pool (uninstalled or marked unavailable) — the pool keeps its
        // default order and the next successful launch refreshes the record.
        val pool = listOf(
            profile("ra_mgba", "com.retroarch", "retroarch-core"),
            profile("snes9x_ex", "com.explusalpha.Snes9xPlus"),
        )

        assertEquals(pool.map { it.id }, pool.stabilizeCore("ra_gambatte").map { it.id })
    }

    @Test
    fun `null remembered core leaves the pool untouched`() {
        val pool = listOf(
            profile("ra_mgba", "com.retroarch", "retroarch-core"),
            profile("snes9x_ex", "com.explusalpha.Snes9xPlus"),
        )

        assertEquals(pool.map { it.id }, pool.stabilizeCore(null).map { it.id })
    }

    @Test
    fun `remembered standalone does not reorder the pool`() {
        // Only RetroArch core launches write the memory, but the guard belongs here anyway.
        val pool = listOf(
            profile("ra_mgba", "com.retroarch", "retroarch-core"),
            profile("snes9x_ex", "com.explusalpha.Snes9xPlus"),
        )

        assertEquals(pool.map { it.id }, pool.stabilizeCore("snes9x_ex").map { it.id })
    }
}
