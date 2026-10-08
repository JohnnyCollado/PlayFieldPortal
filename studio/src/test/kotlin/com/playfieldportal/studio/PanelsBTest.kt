package com.playfieldportal.studio

import com.playfieldportal.studio.io.VideoCodecs
import com.playfieldportal.studio.ui.sections.BackgroundSource
import com.playfieldportal.studio.ui.sections.BootFileInfo
import com.playfieldportal.studio.ui.sections.CropGrab
import com.playfieldportal.studio.ui.sections.CropViewport
import com.playfieldportal.studio.ui.sections.MOTION_CHOICES
import com.playfieldportal.studio.ui.sections.SOUND_ROWS
import com.playfieldportal.studio.ui.sections.backgroundSourceOf
import com.playfieldportal.studio.ui.sections.bootCard
import com.playfieldportal.studio.ui.sections.cropWithZoomFraction
import com.playfieldportal.studio.ui.sections.cropZoomFraction
import com.playfieldportal.studio.ui.sections.formatDuration
import com.playfieldportal.studio.ui.sections.motionControlLabel
import com.playfieldportal.studio.ui.sections.posterLabel
import com.playfieldportal.studio.ui.sections.soundRow
import com.playfieldportal.themekit.PfpThemeManifest
import com.playfieldportal.themekit.PtfIcons
import com.playfieldportal.themekit.WaveStyles
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** TS-29: the pure Background / Sounds / Boot models, and the VM's video re-framing. */
class PanelsBTest {

    // ── Background: source and motion control ───────────────────────────────

    @Test
    fun `source follows what the theme holds`() {
        assertEquals(BackgroundSource.WAVE, backgroundSourceOf(StudioState()))
        assertEquals(BackgroundSource.IMAGE, backgroundSourceOf(StudioState(wallpaperPng = ByteArray(1))))
        assertEquals(
            BackgroundSource.VIDEO,
            backgroundSourceOf(StudioState(wallpaperPng = ByteArray(1), motionFile = File("clip.mp4"))),
        )
    }

    @Test
    fun `motion control label changes with the source`() {
        assertEquals("Wave motion", motionControlLabel(BackgroundSource.WAVE))
        assertEquals("Video playback", motionControlLabel(BackgroundSource.VIDEO))
        assertEquals("Background motion", motionControlLabel(BackgroundSource.IMAGE))
    }

    @Test
    fun `motion choices are the four exact wave values`() {
        assertEquals(
            listOf("animated", "reduced", "static", "reduced_static"),
            MOTION_CHOICES.map { it.first },
        )
        assertEquals(
            listOf("Animated", "Reduced", "Static", "Reduced + Static"),
            MOTION_CHOICES.map { it.second },
        )
        MOTION_CHOICES.forEach { (value, _) -> assertTrue(WaveStyles.isExact(value), value) }
    }

    @Test
    fun `choosing reduced plus static exports the exact value and the static fallback`() {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Unconfined))
        vm.setWaveStyle(MOTION_CHOICES.last().first)
        val manifest = vm.buildManifest()
        assertEquals(PfpThemeManifest.WAVE_REDUCED_STATIC, manifest.waveStyleV4)
        assertEquals(PfpThemeManifest.WAVE_STATIC, manifest.waveStyle)
    }

    @Test
    fun `poster label names the frame or says it is baked`() {
        assertEquals("Still: frame at 0 s", posterLabel(0))
        assertEquals("Still: frame at 1.5 s", posterLabel(1500))
        assertEquals("Still: baked into the theme", posterLabel(null))
    }

    // ── Background: crop gesture mapping (view px -> source px) ─────────────

    // 400x200 view over a square 1000 px source: scale 0.2, letterboxed 100 px each side.
    private val viewport = CropViewport(viewW = 400f, viewH = 200f, sourceW = 1000, sourceH = 1000)

    // HD (16:9) frame zoomed to half width: x=250 y=359.375 w=500 h=281.25 in source px.
    private val frame = CropFrame.centered(1000, 1000, WallpaperPreset.HD).zoomed(2f)

    @Test
    fun `viewport maps view pixels to source pixels and back`() {
        assertEquals(0.2f, viewport.scale, 1e-6f)
        assertEquals(0f, viewport.sourceX(100f), 1e-3f)
        assertEquals(1000f, viewport.sourceX(300f), 1e-3f)
        assertEquals(500f, viewport.sourceY(100f), 1e-3f)
        assertEquals(300f, viewport.viewX(1000f), 1e-3f)
        assertEquals(100f, viewport.viewY(500f), 1e-3f)
    }

    @Test
    fun `hit test finds corners first then the body then nothing`() {
        // Frame in view px: left 150, top 71.875, 100 wide, 56.25 tall.
        assertEquals(CropGrab.Corner(CropFrame.Corner.TOP_LEFT), viewport.grabAt(frame, 151f, 73f))
        assertEquals(CropGrab.Corner(CropFrame.Corner.BOTTOM_RIGHT), viewport.grabAt(frame, 249f, 127f))
        assertEquals(CropGrab.Corner(CropFrame.Corner.TOP_RIGHT), viewport.grabAt(frame, 249f, 73f))
        assertEquals(CropGrab.Corner(CropFrame.Corner.BOTTOM_LEFT), viewport.grabAt(frame, 151f, 127f))
        assertEquals(CropGrab.Body, viewport.grabAt(frame, 200f, 100f))
        assertNull(viewport.grabAt(frame, 10f, 10f))
    }

    @Test
    fun `body drag moves the frame by the view delta in source pixels`() {
        val moved = viewport.drag(frame, CropGrab.Body, dxView = 20f, dyView = -10f, pointerX = 0f, pointerY = 0f)
        assertEquals(frame.x + 100f, moved.x, 1e-3f)
        assertEquals(frame.y - 50f, moved.y, 1e-3f)
        assertEquals(frame.w, moved.w, 1e-3f)
    }

    @Test
    fun `body drag stops at the source edge`() {
        val moved = viewport.drag(frame, CropGrab.Body, dxView = 9999f, dyView = 0f, pointerX = 0f, pointerY = 0f)
        assertEquals(1000f - frame.w, moved.x, 1e-3f)
    }

    @Test
    fun `corner drag pins the opposite corner and keeps the ratio`() {
        // Pointer on the source point (900, bottom edge): 650 px right of the pinned top-left.
        val px = viewport.viewX(900f)
        val py = viewport.viewY(frame.y + frame.h)
        val grab = CropGrab.Corner(CropFrame.Corner.BOTTOM_RIGHT)
        val resized = viewport.drag(frame, grab, dxView = 0f, dyView = 0f, pointerX = px, pointerY = py)
        assertEquals(frame.x, resized.x, 1e-3f)
        assertEquals(frame.y, resized.y, 1e-3f)
        assertEquals(650f, resized.w, 1e-2f)
        assertTrue(abs(resized.w / resized.h - 16f / 9f) < 1e-3f)
    }

    @Test
    fun `zoom fraction runs from the full frame to the smallest and round trips`() {
        val full = CropFrame.centered(1000, 1000, WallpaperPreset.HD)
        assertEquals(0f, cropZoomFraction(full), 1e-6f)
        assertEquals(1f, cropZoomFraction(cropWithZoomFraction(full, 1f)), 1e-4f)
        val half = cropWithZoomFraction(full, 0.5f)
        assertEquals(0.5f, cropZoomFraction(half), 1e-3f)
        assertTrue(abs(half.w / half.h - 16f / 9f) < 1e-3f)
        // Zooming keeps the frame centre.
        assertEquals(full.x + full.w / 2, half.x + half.w / 2, 1e-2f)
    }

    // ── Sounds ──────────────────────────────────────────────────────────────

    @Test
    fun `six sound rows in the documented order`() {
        assertEquals(
            listOf("sound_scroll", "sound_back", "sound_confirm", "sound_error", "sound_notification", "ambience_audio"),
            SOUND_ROWS.map { it.first },
        )
        assertEquals(
            listOf("Navigation", "Back / cancel", "Confirm / apply", "Error / invalid", "Notification", "Ambience (loops)"),
            SOUND_ROWS.map { it.second },
        )
    }

    @Test
    fun `an empty slot is built in and has nothing to play`() {
        val row = soundRow("sound_scroll", "Navigation", extension = null, lengthMs = null)
        assertTrue(row.builtIn)
        assertNull(row.fileName)
        assertFalse(row.canPlay)
        assertNull(row.playHint)
        assertEquals(0f, row.fraction)
    }

    @Test
    fun `a wav plays and shows its length against the cap`() {
        val row = soundRow("sound_scroll", "Navigation", extension = "wav", lengthMs = 250)
        assertFalse(row.builtIn)
        assertEquals("sound_scroll.wav", row.fileName)
        assertTrue(row.canPlay)
        assertNull(row.playHint)
        assertEquals(0.5f, row.fraction, 1e-6f)
        assertEquals("0.25 s of 0.5 s", row.lengthLine)
        assertFalse(row.overCap)
    }

    @Test
    fun `every theme audio format plays in the studio`() {
        for (ext in listOf("mp3", "ogg", "m4a", "MP3")) {
            val row = soundRow("ambience_audio", "Ambience (loops)", extension = ext, lengthMs = 400)
            assertTrue(row.canPlay, ext)
            assertNull(row.playHint, ext)
        }
    }

    @Test
    fun `a format no theme slot takes cannot play and says so`() {
        val row = soundRow("sound_back", "Back / cancel", extension = "flac", lengthMs = 400)
        assertFalse(row.canPlay)
        assertEquals("Plays on the device", row.playHint)
    }

    @Test
    fun `a clip over its cap fills the bar and is flagged`() {
        val row = soundRow("sound_scroll", "Navigation", extension = "wav", lengthMs = 900)
        assertEquals(1f, row.fraction)
        assertTrue(row.overCap)
    }

    @Test
    fun `unknown length leaves an empty bar`() {
        val row = soundRow("sound_confirm", "Confirm / apply", extension = "mp3", lengthMs = null)
        assertEquals(0f, row.fraction)
        assertEquals("Length unknown", row.lengthLine)
        assertFalse(row.overCap)
    }

    @Test
    fun `ambience measures against its ten minute cap`() {
        val row = soundRow("ambience_audio", "Ambience (loops)", extension = "wav", lengthMs = 300_000)
        assertEquals(0.5f, row.fraction, 1e-6f)
        assertEquals("5 min of 10 min", row.lengthLine)
        assertEquals("ambience.wav", row.fileName)
    }

    @Test
    fun `durations read naturally`() {
        assertEquals("1 s", formatDuration(1000))
        assertEquals("0.25 s", formatDuration(250))
        assertEquals("1.5 s", formatDuration(1500))
        assertEquals("10 min", formatDuration(600_000))
    }

    // ── Boot & GameBoot ─────────────────────────────────────────────────────

    @Test
    fun `boot card describes the clip and the limits`() {
        val card = bootCard("boot_video", "Boot", BootFileInfo("mp4", 1_468_006L, 3200L, 1280, 720))
        assertTrue(card.hasVideo)
        assertEquals("boot.mp4", card.fileName)
        assertEquals("boot.mp4 · 3.2 s · 1280×720 · 1.4 MB", card.infoLine)
        assertEquals("1–10 s recommended, 15 s max, 25 MB max", card.limitsLine)
        assertFalse(card.overCap)
    }

    @Test
    fun `boot card without a clip is the built in one`() {
        val card = bootCard("gameboot_video", "GameBoot", null)
        assertFalse(card.hasVideo)
        assertNull(card.infoLine)
        assertNull(card.fileName)
        assertEquals("1–8 s recommended, 10 s max, 25 MB max", card.limitsLine)
    }

    @Test
    fun `boot card leaves out what the probe could not read and flags an overlong clip`() {
        val card = bootCard("gameboot_video", "GameBoot", BootFileInfo("mp4", 2048L, null, null, null))
        assertEquals("gameboot.mp4 · 2 KB", card.infoLine)
        assertTrue(bootCard("boot_video", "Boot", BootFileInfo("mp4", 10L, 16_000L, 640, 360)).overCap)
        assertFalse(bootCard("boot_video", "Boot", BootFileInfo("mp4", 10L, 12_000L, 640, 360)).overCap)
        assertTrue(bootCard("gameboot_video", "GameBoot", BootFileInfo("mp4", 10L, 12_000L, 640, 360)).overCap)
    }

    // ── VM: re-framing an existing video, and picking the poster frame ───────

    private suspend fun StudioViewModel.awaitIdle() {
        withTimeout(30_000) {
            delay(50)
            while (state.value.busy) delay(25)
        }
    }

    @Test
    fun `reframing a video uses the video frame size and rewrites the playback crop`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val dir = createTempDirectory("studio-panelsb").toFile()
        try {
            val video = File(dir, "clip.mp4")
            MotionTestMedia.writeTestMp4(video, width = 320, height = 240)
            vm.importVideo(video)
            vm.awaitIdle()
            assertEquals(0L, vm.state.value.pendingWallpaper?.posterAtMs)
            val first = CropFrame.centered(320, 240, WallpaperPreset.HD)
            vm.confirmWallpaperCrop(first)
            vm.awaitIdle()
            assertEquals(0L, vm.state.value.posterAtMs)

            vm.restageVideoFrame()
            vm.awaitIdle()
            val pending = assertNotNull(vm.state.value.pendingWallpaper)
            assertEquals(320, pending.source.width)
            assertEquals(240, pending.source.height)
            assertTrue(pending.reframesVideo)
            assertEquals(vm.state.value.motionCrop, pending.initialCrop)

            val tighter = first.zoomed(2f)
            vm.confirmWallpaperCrop(tighter)
            vm.awaitIdle()
            assertEquals(tighter.toMotionCrop(), vm.state.value.motionCrop)
            assertTrue(vm.state.value.motionFile?.isFile == true)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `picking a poster frame swaps the staged source and records the time`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val dir = createTempDirectory("studio-panelsb-frame").toFile()
        try {
            val video = File(dir, "clip.mp4")
            MotionTestMedia.writeTestMp4(video, width = 320, height = 240, frames = 10)
            assertNotNull(VideoCodecs.frameAt(video, 0L))

            vm.importVideo(video)
            vm.awaitIdle()
            val atStart = assertNotNull(vm.state.value.pendingWallpaper).source.getRGB(5, 5)

            vm.pickPosterFrame(500L)
            vm.awaitIdle()
            val later = assertNotNull(vm.state.value.pendingWallpaper)
            assertEquals(500L, later.posterAtMs)
            assertTrue(later.source.getRGB(5, 5) != atStart, "a later frame must look different")

            vm.confirmWallpaper(WallpaperPreset.ORIGINAL)
            vm.awaitIdle()
            assertEquals(500L, vm.state.value.posterAtMs)
            assertEquals("clip.mp4", vm.state.value.motionFileName)

            vm.clearMotion()
            assertNull(vm.state.value.posterAtMs)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `picking a frame with no video does nothing`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        vm.pickPosterFrame(100L)
        vm.awaitIdle()
        assertNull(vm.state.value.pendingWallpaper)
    }

    // ── Icon editor: From theme… ─────────────────────────────────────────────

    @Test
    fun `choosing a From theme tile sets the selected slot, and a PSP tile is offered too`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val png = java.io.ByteArrayOutputStream().also {
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", it)
        }.toByteArray()
        vm.update { it.copy(name = "Aurora", ptfIcons = mapOf(PtfIcons.SlotRef(2, 5) to png)) }
        assertTrue(IconPicker.fromThemeAvailable(vm.state.value))

        val section = IconPicker.fromThemeSections(vm.state.value).single()
        assertEquals("More from this PSP theme", section.title)
        vm.setIconFromTheme("catbar_games", section.choices.single().source)
        vm.awaitIdle()

        assertTrue("catbar_games" in vm.state.value.iconOverrides)
        // The slot is now a theme icon too, so the dialog offers it as a "Theme icons" tile.
        assertEquals(listOf("Theme icons", "More from this PSP theme"), IconPicker.fromThemeSections(vm.state.value).map { it.title })
    }
}
