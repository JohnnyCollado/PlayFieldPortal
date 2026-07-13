package com.playfieldportal.core.data.achievement

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AchievementCredentialsProviderTest {

    private val provider = AchievementCredentialsProvider(ApplicationProvider.getApplicationContext())

    @Test
    fun `saves, trims and reads credentials, then clears everything`() = runTest {
        provider.clear()

        provider.saveRetroAchievements("  Chrono  ", "  ra-key-123  ")
        provider.saveSteam("  76561197960287930  ", "  steam-key-456  ")
        provider.setEnabled(true)

        // Identities and keys round-trip, trimmed. (Keys decrypt back to the original value; under
        // the test JVM the Keystore cipher falls back to plaintext, so the round-trip still holds.)
        assertEquals("Chrono", provider.raUsername())
        assertEquals("ra-key-123", provider.raApiKey())
        assertEquals("76561197960287930", provider.steamId64())
        assertEquals("steam-key-456", provider.steamApiKey())
        assertTrue(provider.hasRetroAchievements())
        assertTrue(provider.hasSteam())

        provider.clear()

        assertNull(provider.raUsername())
        assertNull(provider.raApiKey())
        assertNull(provider.steamId64())
        assertNull(provider.steamApiKey())
        assertFalse(provider.hasRetroAchievements())
        assertFalse(provider.hasSteam())
    }
}
