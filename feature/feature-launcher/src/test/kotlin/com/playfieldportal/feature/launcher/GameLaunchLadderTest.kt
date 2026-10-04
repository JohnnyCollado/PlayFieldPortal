package com.playfieldportal.feature.launcher

import com.playfieldportal.core.data.database.dao.PlatformDao
import com.playfieldportal.core.data.database.entity.PlatformEntity
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.IntentType
import com.playfieldportal.core.domain.model.MemoryCard
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ladder's inputs, gathered once for every launch path. Game Detail and the XMB's direct
 * launch both ask this, so a console default assigned in Settings decides an XMB launch exactly as
 * it decides a Game Detail one (it used to be skipped there: direct launch took the first installed
 * emulator whatever the console's card said).
 */
class GameLaunchLadderTest {

    private fun profile(id: String, platforms: List<String> = listOf("x360")) = EmulatorProfile(
        id = id,
        name = id,
        packageName = "pkg.$id",
        intentType = IntentType.ACTION_VIEW,
        supportedPlatformIds = platforms,
        isAvailable = true,
    )

    private val first = profile("first")      // the automatic pick: first in launch preference
    private val assigned = profile("assigned") // the console default the user chose

    private fun ladder(cardEmulator: String? = null, platformDefault: String? = null): GameLaunchLadder {
        val profiles = mockk<EmulatorProfileRepository> {
            every { getInstalledProfiles() } returns listOf(first, assigned)
        }
        val cards = mockk<MemoryCardRepository> {
            coEvery { getById("x360") } returns MemoryCard(platformId = "x360", displayName = "Xbox 360 Memory Card", emulatorId = cardEmulator)
        }
        val platforms = mockk<PlatformDao> {
            coEvery { getById("x360") } returns PlatformEntity(
                id = "x360", name = "Xbox 360", shortName = "X360", iconRes = "", accentColor = 0L,
                romExtensions = "iso", preferredEmulatorPackage = platformDefault,
            )
        }
        val memory = mockk<AutoCoreMemory> { coEvery { rememberedProfileId(any()) } returns null }
        return GameLaunchLadder(profiles, cards, platforms, memory)
    }

    private val game = Game(title = "Halo 3", platformId = "x360", romPath = "/roms/x360/halo3.iso")

    @Test
    fun `a game on the platform default launches with the console's assigned emulator`() = runTest {
        val resolved = ladder(cardEmulator = "assigned").resolve(game).getOrThrow()
        assertEquals("assigned", resolved.profile.id)
        assertEquals(LaunchSource.MEMORY_CARD, resolved.source)
    }

    @Test
    fun `the platform record's default applies when the console has none`() = runTest {
        val resolved = ladder(platformDefault = "pkg.assigned").resolve(game).getOrThrow()
        assertEquals("assigned", resolved.profile.id)
        assertEquals(LaunchSource.PLATFORM_DEFAULT, resolved.source)
    }

    @Test
    fun `a per-game choice still beats the console default`() = runTest {
        val resolved = ladder(cardEmulator = "assigned").resolve(game.copy(emulatorPackage = "first")).getOrThrow()
        assertEquals("first", resolved.profile.id)
    }

    @Test
    fun `with nothing assigned the first installed emulator launches`() = runTest {
        val resolved = ladder().resolve(game).getOrThrow()
        assertEquals("first", resolved.profile.id)
        assertEquals(LaunchSource.CATALOG_DEFAULT, resolved.source)
    }
}
