package com.playfieldportal.feature.launcher

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.IntentType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * Plain-JVM preflight tests for the B1 additions to [EmulatorIntentResolver]. The Robolectric suite
 * ([EmulatorIntentResolverTest]) covers intent shape; the revoked-SAF-grant path needs a content
 * resolver that actually throws SecurityException, which Robolectric's ShadowContentResolver cannot
 * produce — so these use a mockk context instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EmulatorIntentResolverPreflightTest {

    private fun contextWith(
        packageInstalled: Boolean = true,
        openFd: () -> Unit = {},
    ): Context {
        val pm = mockk<PackageManager>()
        every { pm.getPackageInfo(any<String>(), any<Int>()) } answers {
            if (!packageInstalled) throw PackageManager.NameNotFoundException("gone")
            mockk()
        }
        // COMPONENT preflight also asks for the pinned activity; any ActivityInfo satisfies it.
        every { pm.getActivityInfo(any(), any<Int>()) } returns mockk()
        val resolver = mockk<ContentResolver>()
        every { resolver.openFileDescriptor(any(), any()) } answers {
            openFd()
            null
        }
        return mockk<Context>().apply {
            every { this@apply.packageManager } returns pm
            every { this@apply.contentResolver } returns resolver
        }
    }

    private val romUri = "content://com.android.externalstorage.documents/document/roms%2Fgame.bin"

    private fun safGame() = Game(title = "Crash", platformId = "psx", romUri = romUri)

    private fun componentProfile() = EmulatorProfile(
        id = "test",
        name = "Emu",
        packageName = "com.emu",
        activityClass = "com.emu.Activity",
        intentType = IntentType.COMPONENT,
        supportedPlatformIds = listOf("psx"),
    )

    @Test
    fun `saf game whose rom grant is revoked fails with a reconnect hint`() {
        val context = contextWith { throw SecurityException("Permission Denial: opening provider") }
        val resolver = EmulatorIntentResolver(context, mockk(relaxed = true))

        val result = runBlocking { resolver.resolve(safGame(), componentProfile()) }

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()!!.message!!.contains("lost access", ignoreCase = true),
            "Expected a reconnect hint, got: ${result.exceptionOrNull()!!.message}",
        )
    }

    @Test
    fun `saf game whose rom is reachable passes the grant probe`() {
        val context = contextWith(openFd = {})
        val resolver = EmulatorIntentResolver(context, mockk(relaxed = true))

        val result = runBlocking { resolver.resolve(safGame(), componentProfile()) }

        // The probe itself is what we're pinning — a reachable URI must NOT be refused by it.
        assertTrue(
            result.exceptionOrNull()?.message?.contains("lost access", ignoreCase = true) != true,
            "reachable grant must not be refused by the probe; failure: ${result.exceptionOrNull()?.message}",
        )
    }
}
