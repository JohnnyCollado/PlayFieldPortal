package com.playfieldportal.feature.xmb.viewmodel

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.playfieldportal.core.data.repository.ThemePrefKeys
import com.playfieldportal.core.data.repository.ThemeTiers
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.core.domain.model.XmbColorScheme
import com.playfieldportal.core.ui.icons.CustomIcon
import com.playfieldportal.core.ui.icons.IconTier
import com.playfieldportal.core.ui.media.UiMediaPaths
import com.playfieldportal.themekit.XmbLayoutSpec
import com.playfieldportal.themekit.XmbLayoutSpecCodec
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The launcher's theme look as one value: the colours, both icon tiers, the XMB geometry and the
 * boot media, derived from the prefs and the tiers. Icons are decoded only when an icon stamp
 * moves — a text-colour or layout edit must not re-decode two folders of bitmaps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThemeLookTest {

    private class Harness(io: kotlinx.coroutines.CoroutineDispatcher) {
        val prefs = MutableStateFlow<Preferences>(mutablePreferencesOf())
        val loads = mutableListOf<ThemeTiers.Tier>()
        val userIcon = CustomIcon.Still(mockk<ImageBitmap>())
        val themeIcon = CustomIcon.Still(mockk<ImageBitmap>())
        val mediaStamp = MutableStateFlow(0L)
        var bootVideo: String? = null
        val media = object : UiMediaPaths {
            override fun pathFor(slot: UiMediaSlot): String? = if (slot == UiMediaSlot.BOOT_VIDEO) bootVideo else null
            override val stamp = mediaStamp
        }
        val look = ThemeLook(
            prefs = prefs,
            loadIcons = { tier ->
                loads += tier
                when (tier) {
                    ThemeTiers.Tier.USER -> mapOf("catbar_games" to userIcon)
                    ThemeTiers.Tier.THEME -> mapOf("catbar_games" to themeIcon, "catbar_music" to themeIcon)
                }
            },
            uiMedia = media,
            packageName = "com.playfieldportal.test",
            month = { 10 },
            io = io,
        )

        fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
            prefs.value = prefs.value.toMutablePreferences().also(block)
        }
    }

    @Test
    fun `the stock look is the classic preset with no icons and the default geometry`() = runTest {
        val h = Harness(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val look = h.look.observe().first()
        assertEquals(XmbColorScheme.CLASSIC_BLUE.name, look.schemeName)
        assertEquals(XmbLayoutSpec.DEFAULT, look.layoutSpec)
        assertEquals(1f, look.xmbScale)
        assertNull(look.icons["catbar_games"])
        assertEquals(emptyList(), h.loads, "no stamp, no decode")
    }

    @Test
    fun `both tiers load once their stamps are present, the user's pick on top`() = runTest {
        val h = Harness(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        h.edit {
            it[ThemePrefKeys.THEME_ICONS_STAMP] = 1L
            it[com.playfieldportal.core.data.repository.CustomIconStore.KEY_CUSTOM_ICONS_STAMP] = 1L
        }
        val look = h.look.observe().first()
        assertSame(h.userIcon, look.icons["catbar_games"])
        assertSame(h.themeIcon, look.icons["catbar_music"])
        assertEquals(IconTier.THEME, look.icons.tierOf("catbar_music"))
    }

    @Test
    fun `a text colour edit emits a new look without decoding icons again`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        h.edit { it[ThemePrefKeys.THEME_ICONS_STAMP] = 1L }
        val looks = mutableListOf<XmbThemeLook>()
        val job = launch { h.look.observe().take(2).toList(looks) }
        advanceUntilIdle()
        h.edit { it[ThemePrefKeys.TEXT_COLOR] = 0xFF112233L }
        advanceUntilIdle()
        job.join()

        assertEquals(Color(0xFF112233), looks.last().colors.textOverride)
        assertEquals(listOf(ThemeTiers.Tier.THEME), h.loads, "decoded once, for the stamp only")
    }

    @Test
    fun `a stamp bump re-decodes only its own tier`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        h.edit {
            it[ThemePrefKeys.THEME_ICONS_STAMP] = 1L
            it[com.playfieldportal.core.data.repository.CustomIconStore.KEY_CUSTOM_ICONS_STAMP] = 1L
        }
        val job = launch { h.look.observe().take(2).toList(mutableListOf()) }
        advanceUntilIdle()
        h.edit { it[com.playfieldportal.core.data.repository.CustomIconStore.KEY_CUSTOM_ICONS_STAMP] = 2L }
        advanceUntilIdle()
        job.join()

        assertEquals(1, h.loads.count { it == ThemeTiers.Tier.THEME })
        assertEquals(2, h.loads.count { it == ThemeTiers.Tier.USER })
    }

    @Test
    fun `an accent override tints the wave and the user's bar position wins over the theme's`() = runTest {
        val h = Harness(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        h.edit {
            it[ThemePrefKeys.ACCENT_OVERRIDE] = 0xFFFF0000L
            it[ThemePrefKeys.THEME_LAYOUT] = XmbLayoutSpecCodec.encode(XmbLayoutSpec(barTopFraction = 0.2f))
            it[ThemeLook.KEY_BAR_TOP_FRACTION] = 0.3f
            it[ThemeLook.KEY_XMB_SCALE] = 9f
        }
        val look = h.look.observe().first()
        assertEquals(Color(0xFFFF0000), look.colors.waveColor)
        assertEquals(0.3f, look.layoutSpec.barTopFraction)
        assertEquals(1.3f, look.xmbScale, "the scale is clamped")
    }

    @Test
    fun `the boot media follow the UI media stamp`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val looks = mutableListOf<XmbThemeLook>()
        val job = launch { h.look.observe().take(2).toList(looks) }
        advanceUntilIdle()
        h.bootVideo = "/files/ui-media/boot_video.mp4"
        h.mediaStamp.value = 1L
        advanceUntilIdle()
        job.join()

        assertNull(looks.first().bootVideoPath)
        assertEquals("/files/ui-media/boot_video.mp4", looks.last().bootVideoPath)
        assertNull(looks.last().bootAudioPath, "a custom boot video keeps its own track")
    }
}
