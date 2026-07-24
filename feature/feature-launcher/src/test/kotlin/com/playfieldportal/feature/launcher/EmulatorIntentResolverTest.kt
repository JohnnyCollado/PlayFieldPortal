package com.playfieldportal.feature.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.IntentType
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Intent shapes for the launch recipes verified in docs/emulator-intent-catalog-research.md.
 * SAF games (romUri set) are used throughout so no FileProvider registration is needed.
 */
@RunWith(RobolectricTestRunner::class)
class EmulatorIntentResolverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resolver = EmulatorIntentResolver(context)

    private val romUri = "content://com.android.externalstorage.documents/document/roms%2Fgame.bin"
    private fun safGame(platformId: String = "switch") =
        Game(title = "Test Game", platformId = platformId, romUri = romUri)

    private fun installPackage(packageName: String) {
        shadowOf(context.packageManager)
            .installPackage(PackageInfo().apply { this.packageName = packageName })
    }

    private fun registerViewActivity(packageName: String, activityClass: String) {
        val component = ComponentName(packageName, activityClass)
        val shadowPm = shadowOf(context.packageManager)
        shadowPm.addActivityIfNotPresent(component)
        shadowPm.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("content")
                addDataType("application/octet-stream")
            },
        )
    }

    private fun Intent.hasFlags(flags: Int) = this.flags and flags == flags

    // ── attachRomData (yuzu-lineage TECH_DISCOVERED launch) ──────────────────

    @Test
    fun `attachRomData component intent carries rom as data uri with read grant`() {
        installPackage("dev.eden.eden_emulator")
        val profile = EmulatorProfile(
            id = "test_eden",
            name = "Eden",
            packageName = "dev.eden.eden_emulator",
            activityClass = "org.yuzu.yuzu_emu.activities.EmulationActivity",
            intentType = IntentType.COMPONENT,
            supportedPlatformIds = listOf("switch"),
            intentAction = "android.nfc.action.TECH_DISCOVERED",
            attachRomData = true,
        )

        val intent = resolver.resolve(safGame(), profile).getOrThrow()

        assertEquals("android.nfc.action.TECH_DISCOVERED", intent.action)
        assertEquals(
            "org.yuzu.yuzu_emu.activities.EmulationActivity",
            intent.component?.className,
        )
        assertEquals(romUri, intent.data.toString())
        assertTrue(intent.hasFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        assertNotNull(intent.clipData, "clipData grant missing for the ROM uri")
    }

    // ── {rom_uri} string extras (DuckStation bootPath recipe) ────────────────

    @Test
    fun `rom_uri extra resolves to the saf uri with bool extras and clear flags`() {
        installPackage("com.github.stenzek.duckstation")
        val profile = EmulatorProfile(
            id = "test_duckstation",
            name = "DuckStation",
            packageName = "com.github.stenzek.duckstation",
            activityClass = "com.github.stenzek.duckstation.EmulationActivity",
            intentType = IntentType.COMPONENT,
            supportedPlatformIds = listOf("psx"),
            intentExtras = mapOf("bootPath" to "{rom_uri}"),
            intentBoolExtras = mapOf("resumeState" to false),
            intentFlags = listOf("CLEAR_TASK", "CLEAR_TOP"),
        )

        val intent = resolver.resolve(safGame("psx"), profile).getOrThrow()

        assertEquals(romUri, intent.getStringExtra("bootPath"))
        assertEquals(false, intent.getBooleanExtra("resumeState", true))
        assertNull(intent.data, "data must stay unset without attachRomData")
        assertTrue(
            intent.hasFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        )
    }

    @Test
    fun `custom action component intent keeps rom in extras only`() {
        installPackage("me.magnum.melonds")
        val profile = EmulatorProfile(
            id = "test_melonds",
            name = "melonDS",
            packageName = "me.magnum.melonds",
            activityClass = "me.magnum.melonds.ui.emulator.EmulatorActivity",
            intentType = IntentType.COMPONENT,
            supportedPlatformIds = listOf("nds"),
            intentAction = "me.magnum.melonds.LAUNCH_ROM",
            intentExtras = mapOf("uri" to "{rom_uri}"),
        )

        val intent = resolver.resolve(safGame("nds"), profile).getOrThrow()

        assertEquals("me.magnum.melonds.LAUNCH_ROM", intent.action)
        assertEquals(romUri, intent.getStringExtra("uri"))
        assertNull(intent.data)
    }

    // ── ACTION_VIEW launches ─────────────────────────────────────────────────

    @Test
    fun `view intent applies profile clear flags and grants read on the content uri`() {
        installPackage("com.sky.SkyEmu")
        registerViewActivity("com.sky.SkyEmu", "com.sky.SkyEmu.EnhancedNativeActivity")
        val profile = EmulatorProfile(
            id = "test_skyemu",
            name = "SkyEmu",
            packageName = "com.sky.SkyEmu",
            activityClass = "com.sky.SkyEmu.EnhancedNativeActivity",
            intentType = IntentType.ACTION_VIEW,
            supportedPlatformIds = listOf("gba"),
            intentFlags = listOf("CLEAR_TASK", "CLEAR_TOP"),
            mimeType = "application/octet-stream",
        )

        val intent = resolver.resolve(safGame("gba"), profile).getOrThrow()

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(romUri, intent.data.toString())
        assertTrue(
            intent.hasFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        )
        assertNotNull(intent.clipData)
    }

    // ── validation ───────────────────────────────────────────────────────────

    @Test
    fun `missing emulator fails with a readable message instead of throwing`() {
        val profile = EmulatorProfile(
            id = "test_missing",
            name = "Ghost Emulator",
            packageName = "com.not.installed",
            activityClass = "com.not.installed.Main",
            intentType = IntentType.COMPONENT,
            supportedPlatformIds = listOf("psx"),
            intentExtras = mapOf("bootPath" to "{rom_uri}"),
        )

        val result = resolver.resolve(safGame("psx"), profile)

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()!!.message!!.contains("not installed"),
            "Expected a user-readable install hint, got: ${result.exceptionOrNull()!!.message}",
        )
    }
}
