package com.playfieldportal.studio

import com.playfieldportal.themekit.MotionCrop
import com.playfieldportal.themekit.ThemeLegibility
import com.playfieldportal.themekit.UiMediaLimits
import com.playfieldportal.themekit.UpgradeReport
import java.io.File
import java.io.RandomAccessFile
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExportCheckTest {

    private val dir: File = createTempDirectory("studio-export-check").toFile()
    private val mb = 1024L * 1024

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun file(name: String, bytes: Long) =
        File(dir, name).also { RandomAccessFile(it, "rw").use { raf -> raf.setLength(bytes) } }

    private fun item(check: ExportCheck, id: CheckId) = check.items.firstOrNull { it.id == id }

    @Test
    fun `budget sums per kind and in total`() {
        val state = StudioState(
            wallpaperPng = ByteArray(1000),
            previewPng = ByteArray(200),
            iconOverrides = mapOf("a" to ByteArray(30), "b" to ByteArray(20), "sysicon_psx" to ByteArray(10)),
            motionFile = file("m.mp4", 5000),
            mediaFiles = mapOf(
                "sound_scroll" to file("s1.wav", 100),
                "sound_back" to file("s2.wav", 50),
                "ambience_audio" to file("amb.wav", 700),
                "boot_video" to file("boot.mp4", 4000),
                "gameboot_video" to file("gb.mp4", 3000),
            ),
            passthroughFiles = mapOf("extras/readme.txt" to file("p.txt", 7)),
        )
        val c = ExportCheck.of(state)
        assertEquals(1000L, c.bytesByKind[BudgetKind.WALLPAPER])
        assertEquals(200L, c.bytesByKind[BudgetKind.PREVIEW])
        assertEquals(60L, c.bytesByKind[BudgetKind.ICONS])
        assertEquals(5000L, c.bytesByKind[BudgetKind.MOTION])
        assertEquals(150L, c.bytesByKind[BudgetKind.SOUNDS])
        assertEquals(700L, c.bytesByKind[BudgetKind.AMBIENCE])
        assertEquals(4000L, c.bytesByKind[BudgetKind.BOOT])
        assertEquals(3000L, c.bytesByKind[BudgetKind.GAMEBOOT])
        assertEquals(7L, c.bytesByKind[BudgetKind.OTHER])
        assertEquals(1000L + 200 + 60 + 5000 + 150 + 700 + 4000 + 3000 + 7, c.totalBytes)
    }

    @Test
    fun `an empty theme is clean`() {
        val c = ExportCheck.of(StudioState())
        assertEquals(0L, c.totalBytes)
        assertTrue(c.errors.isEmpty())
        assertTrue(c.warnings.isEmpty())
        assertTrue(c.canExport)
        assertEquals(CheckSeverity.OK, item(c, CheckId.MEDIA_LIMITS)?.severity)
        assertEquals(CheckSeverity.OK, item(c, CheckId.OLDER_LAUNCHERS)?.severity)
        assertNull(item(c, CheckId.MOTION_POSTER), "no motion, no poster line")
        assertNull(c.upgrade)
    }

    @Test
    fun `64 MB warns for older launchers only strictly above the cap`() {
        val at = ExportCheck.of(StudioState(passthroughFiles = mapOf("x/a.bin" to file("a.bin", 64 * mb)), wallpaperPng = ByteArray(1)))
        // 64 MB of extras + 1 byte wallpaper is over by one byte.
        assertEquals(CheckSeverity.WARNING, item(at, CheckId.OLDER_LAUNCHERS)?.severity)
        val exactly = ExportCheck.of(StudioState(passthroughFiles = mapOf("x/b.bin" to file("b.bin", 64 * mb))))
        assertEquals(64 * mb, exactly.totalBytes)
        assertNotNull(item(exactly, CheckId.OLDER_LAUNCHERS))
        assertTrue(exactly.warnings.none { it.id == CheckId.OLDER_LAUNCHERS }, "exactly 64 MB still opens")
        assertTrue(at.canExport)
    }

    @Test
    fun `256 MB is a hard error`() {
        val c = ExportCheck.of(StudioState(motionFile = file("huge.mp4", 256 * mb + 1)))
        assertEquals(CheckSeverity.ERROR, item(c, CheckId.SIZE)?.severity)
        assertTrue(!c.canExport)
        val ok = ExportCheck.of(StudioState(motionFile = file("edge.mp4", 256 * mb)))
        assertTrue(ok.errors.none { it.id == CheckId.SIZE })
    }

    @Test
    fun `motion without a still is an error and with one is ok`() {
        val bare = ExportCheck.of(StudioState(motionFile = file("v.mp4", 10)))
        assertEquals(CheckSeverity.ERROR, item(bare, CheckId.MOTION_POSTER)?.severity)
        assertTrue(!bare.canExport)
        val ok = ExportCheck.of(StudioState(motionFile = file("v2.mp4", 10), wallpaperPng = ByteArray(4)))
        assertEquals(CheckSeverity.OK, item(ok, CheckId.MOTION_POSTER)?.severity)
    }

    @Test
    fun `media over its slot cap or missing from disk is an error that names it`() {
        val big = file("big.wav", UiMediaLimits.THEME_SOUND_MAX_BYTES + 1)
        val gone = File(dir, "gone.mp4")
        val c = ExportCheck.of(StudioState(mediaFiles = mapOf("sound_scroll" to big, "boot_video" to gone)))
        val media = item(c, CheckId.MEDIA_LIMITS)
        assertEquals(CheckSeverity.ERROR, media?.severity)
        assertTrue(media!!.message.contains("sound_scroll"), media.message)
        assertTrue(media.message.contains("boot_video"), media.message)
        assertTrue(!c.canExport)
    }

    @Test
    fun `busy wallpaper without legibility warns, with a plate does not`() {
        val busy = StudioState(wallpaperPng = ByteArray(1), wallpaperBusy = true)
        assertEquals(CheckSeverity.WARNING, item(ExportCheck.of(busy), CheckId.TEXT_CONTRAST)?.severity)
        val none = busy.copy(legibility = ThemeLegibility(text = "none"))
        assertEquals(CheckSeverity.WARNING, item(ExportCheck.of(none), CheckId.TEXT_CONTRAST)?.severity)
        val plate = busy.copy(legibility = ThemeLegibility(text = "plate"))
        assertEquals(CheckSeverity.OK, item(ExportCheck.of(plate), CheckId.TEXT_CONTRAST)?.severity)
        val calm = StudioState(wallpaperPng = ByteArray(1), wallpaperBusy = false)
        assertEquals(CheckSeverity.OK, item(ExportCheck.of(calm), CheckId.TEXT_CONTRAST)?.severity)
        assertNull(item(ExportCheck.of(StudioState()), CheckId.TEXT_CONTRAST), "no wallpaper, no contrast line")
    }

    @Test
    fun `v4-only content is an info line about older launchers`() {
        val plain = ExportCheck.of(StudioState())
        assertEquals(CheckSeverity.OK, item(plain, CheckId.OLDER_LAUNCHERS)?.severity)
        val v4 = ExportCheck.of(StudioState(legibility = ThemeLegibility(text = "shadow")))
        assertEquals(CheckSeverity.INFO, item(v4, CheckId.OLDER_LAUNCHERS)?.severity)
        val crop = ExportCheck.of(StudioState(motionCrop = MotionCrop(0f, 0f, 1f, 1f)))
        assertEquals(CheckSeverity.INFO, item(crop, CheckId.OLDER_LAUNCHERS)?.severity)
        val media = ExportCheck.of(StudioState(mediaFiles = mapOf("sound_scroll" to file("s.wav", 10))))
        assertEquals(CheckSeverity.INFO, item(media, CheckId.OLDER_LAUNCHERS)?.severity)
    }

    @Test
    fun `upgrade report is passed through and unrecoverable parts warn`() {
        val report = UpgradeReport(
            kept = listOf("Wallpaper"), added = listOf("Legibility"), repaired = emptyList(), cantRecover = emptyList(),
        )
        val clean = ExportCheck.of(StudioState(upgradeReport = report, schemaVersion = 3))
        assertEquals(report, clean.upgrade)
        assertEquals(CheckSeverity.INFO, item(clean, CheckId.UPGRADE)?.severity)
        val lossy = ExportCheck.of(StudioState(upgradeReport = report.copy(cantRecover = listOf("evil/../x.png"))))
        assertEquals(CheckSeverity.WARNING, item(lossy, CheckId.UPGRADE)?.severity)
        assertNull(item(ExportCheck.of(StudioState()), CheckId.UPGRADE))
    }
}
