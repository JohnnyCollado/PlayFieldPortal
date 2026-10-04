package com.playfieldportal.studio

import androidx.compose.ui.input.key.Key
import com.playfieldportal.studio.ui.StudioSection
import com.playfieldportal.studio.ui.ToolbarAction
import com.playfieldportal.studio.ui.ToolbarFiles
import com.playfieldportal.studio.ui.contentsModel
import com.playfieldportal.studio.ui.formatBytes
import com.playfieldportal.studio.ui.railMeta
import com.playfieldportal.studio.ui.runToolbarAction
import com.playfieldportal.studio.ui.shortcutAction
import com.playfieldportal.themekit.ThemeLegibility
import java.io.File
import java.io.RandomAccessFile
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope

/** Pure logic behind the restructured shell: sections, rail meta, budget strip, toolbar table, shortcuts. */
class ShellLogicTest {

    private val dir: File = createTempDirectory("studio-shell").toFile()
    private val mb = 1024L * 1024

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun file(name: String, bytes: Long) =
        File(dir, name).also { RandomAccessFile(it, "rw").use { raf -> raf.setLength(bytes) } }

    private fun meta(section: StudioSection, state: StudioState) =
        railMeta(section, state, ExportCheck.of(state))

    // ── Sections ──

    @Test
    fun `nine sections in rail order with unique ids`() {
        assertEquals(
            listOf("info", "color", "background", "legibility", "layout", "icons", "sounds", "boot", "export"),
            StudioSection.entries.map { it.id },
        )
        assertEquals(StudioSection.entries.size, StudioSection.entries.map { it.id }.toSet().size)
        assertEquals("Boot & GameBoot", StudioSection.BOOT.label)
        assertEquals("Export check", StudioSection.EXPORT_CHECK.label)
    }

    @Test
    fun `fromId resolves known ids and falls back to Info`() {
        StudioSection.entries.forEach { assertEquals(it, StudioSection.fromId(it.id)) }
        assertEquals(StudioSection.INFO, StudioSection.fromId("nope"))
        assertEquals(StudioSection.INFO, StudioSection.fromId(null))
    }

    // ── Rail meta ──

    @Test
    fun `rail meta for a fresh theme`() {
        val s = StudioState()
        assertEquals("Untitled Theme", meta(StudioSection.INFO, s))
        assertTrue(meta(StudioSection.COLOR, s).startsWith("#"))
        assertEquals("Wave", meta(StudioSection.BACKGROUND, s))
        assertEquals("Not set", meta(StudioSection.LEGIBILITY, s))
        assertEquals("Preview only", meta(StudioSection.LAYOUT, s))
        assertEquals("None custom", meta(StudioSection.ICONS, s))
        assertEquals("None set", meta(StudioSection.SOUNDS, s))
        assertEquals("None set", meta(StudioSection.BOOT, s))
        assertEquals("Ready", meta(StudioSection.EXPORT_CHECK, s))
    }

    @Test
    fun `rail meta reflects content`() {
        val s = StudioState(
            name = "  ",
            wallpaperPng = ByteArray(10),
            iconOverrides = mapOf("a" to ByteArray(1), "b" to ByteArray(1), "sysicon_psx" to ByteArray(1)),
            legibility = ThemeLegibility(text = "outline"),
            layout = com.playfieldportal.themekit.XmbLayoutSpec.DEFAULT.copy(barTopFraction = 0.4f),
            mediaFiles = mapOf(
                "sound_scroll" to file("s.wav", 10),
                "ambience_audio" to file("a.wav", 10),
                "boot_video" to file("b.mp4", 10),
            ),
        )
        assertEquals("Untitled", meta(StudioSection.INFO, s))
        assertEquals("Image", meta(StudioSection.BACKGROUND, s))
        assertEquals("Set", meta(StudioSection.LEGIBILITY, s))
        // The theme carries no XMB sizes, so an imported layout changes nothing here.
        assertEquals("Preview only", meta(StudioSection.LAYOUT, s))
        assertEquals("3 custom", meta(StudioSection.ICONS, s))
        assertEquals("2 set", meta(StudioSection.SOUNDS, s))
        assertEquals("1 of 2", meta(StudioSection.BOOT, s))
        assertEquals("Video", meta(StudioSection.BACKGROUND, s.copy(motionFile = file("m.mp4", 10))))
    }

    @Test
    fun `export check rail meta counts errors before warnings`() {
        // Motion with no poster is an ERROR row.
        val bad = StudioState(motionFile = file("m.mp4", 10))
        assertEquals("1 error", meta(StudioSection.EXPORT_CHECK, bad))
        val big = StudioState(passthroughFiles = mapOf("x/y.bin" to file("big.bin", 70 * mb)))
        assertEquals("1 warning", meta(StudioSection.EXPORT_CHECK, big))
    }

    // ── Contents / budget strip ──

    @Test
    fun `contents model lists non-empty kinds with shares of the total`() {
        val check = ExportCheck.of(
            StudioState(wallpaperPng = ByteArray(300), previewPng = ByteArray(100)),
        )
        val m = contentsModel(check)
        assertEquals(listOf(BudgetKind.WALLPAPER, BudgetKind.PREVIEW), m.segments.map { it.kind })
        assertEquals(0.75f, m.segments[0].share, 0.001f)
        assertEquals(400L, m.totalBytes)
        assertFalse(m.overPreV4Limit)
        assertFalse(m.overHardLimit)
        assertTrue(m.usedOfHard in 0f..1f)
    }

    @Test
    fun `contents model flags the 64 MB pre-v4 line and the hard cap`() {
        val over64 = contentsModel(ExportCheck.of(StudioState(passthroughFiles = mapOf("a/b" to file("b1", 70 * mb)))))
        assertTrue(over64.overPreV4Limit)
        assertFalse(over64.overHardLimit)
        val overHard = contentsModel(
            ExportCheck(mapOf(BudgetKind.MOTION to ExportCheck.HARD_MAX_BYTES + 1), emptyList(), null),
        )
        assertTrue(overHard.overHardLimit)
        assertEquals(1f, overHard.usedOfHard)
    }

    @Test
    fun `empty contents has no segments and zero shares`() {
        val m = contentsModel(ExportCheck.of(StudioState()))
        assertTrue(m.segments.isEmpty())
        assertEquals(0L, m.totalBytes)
        assertEquals("0 KB", formatBytes(0))
    }

    @Test
    fun `byte formatting`() {
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 MB", formatBytes((1.5 * mb).toLong()))
        assertEquals("12 KB", formatBytes(12 * 1024))
    }

    // ── Shortcuts ──

    @Test
    fun `undo redo shortcuts`() {
        assertEquals(ToolbarAction.UNDO, shortcutAction(Key.Z, ctrl = true, shift = false))
        assertEquals(ToolbarAction.REDO, shortcutAction(Key.Z, ctrl = true, shift = true))
        assertEquals(ToolbarAction.REDO, shortcutAction(Key.Y, ctrl = true, shift = false))
        assertNull(shortcutAction(Key.Z, ctrl = false, shift = false))
        assertNull(shortcutAction(Key.Y, ctrl = false, shift = false))
        assertNull(shortcutAction(Key.A, ctrl = true, shift = false))
    }

    // ── Toolbar action table ──

    private class FakeFiles(
        val opens: MutableList<Pair<String, Set<String>>> = mutableListOf(),
        val saves: MutableList<Triple<String, String, String>> = mutableListOf(),
        val folders: MutableList<String> = mutableListOf(),
    ) : ToolbarFiles {
        override fun open(title: String, extensions: Set<String>): File? = null.also { opens += title to extensions }
        override fun save(title: String, suggestedName: String, extension: String): File? =
            null.also { saves += Triple(title, suggestedName, extension) }
        override fun folder(title: String): File? = null.also { folders += title }
    }

    @Test
    fun `enabled table follows history and busy`() {
        fun on(a: ToolbarAction, undo: Boolean = false, redo: Boolean = false, busy: Boolean = false) =
            a.isEnabled(canUndo = undo, canRedo = redo, busy = busy)
        assertFalse(on(ToolbarAction.UNDO))
        assertTrue(on(ToolbarAction.UNDO, undo = true))
        assertFalse(on(ToolbarAction.UNDO, undo = true, busy = true))
        assertFalse(on(ToolbarAction.REDO, undo = true))
        assertTrue(on(ToolbarAction.REDO, redo = true))
        assertTrue(on(ToolbarAction.NEW))
        assertTrue(on(ToolbarAction.EXPORT))
        assertFalse(on(ToolbarAction.EXPORT, busy = true))
        assertFalse(on(ToolbarAction.OPEN, busy = true))
    }

    @Test
    fun `new undo and redo drive the view model`() {
        val vm = StudioViewModel(TestScope())
        val files = FakeFiles()
        vm.setName("Mine")
        runToolbarAction(ToolbarAction.UNDO, vm, files)
        assertEquals("Untitled Theme", vm.state.value.name)
        runToolbarAction(ToolbarAction.REDO, vm, files)
        assertEquals("Mine", vm.state.value.name)
        runToolbarAction(ToolbarAction.NEW, vm, files)
        assertEquals("Untitled Theme", vm.state.value.name)
    }

    @Test
    fun `file actions ask for the same files as before and a cancel changes nothing`() {
        val vm = StudioViewModel(TestScope())
        vm.setName("Keep")
        val files = FakeFiles()
        runToolbarAction(ToolbarAction.OPEN, vm, files)
        runToolbarAction(ToolbarAction.CONVERT_PTF, vm, files)
        runToolbarAction(ToolbarAction.UNPACK_ASSETS, vm, files)
        runToolbarAction(ToolbarAction.BATCH_CONVERT, vm, files)
        runToolbarAction(ToolbarAction.UPGRADE_FOLDER, vm, files)
        runToolbarAction(ToolbarAction.EXPORT, vm, files)

        assertEquals(setOf("ptf", "ctf", "pfptheme"), files.opens[0].second)
        assertEquals(setOf("ptf", "ctf"), files.opens[1].second)
        assertEquals(setOf("ptf", "ctf"), files.opens[2].second)
        // Unpack stops at its cancelled file pick (no folder asked); batch stops at its first
        // folder; upgrade asks one folder.
        assertEquals(2, files.folders.size)
        assertEquals("Keep.pfptheme", files.saves.single().second)
        assertEquals("pfptheme", files.saves.single().third)
        assertEquals("Keep", vm.state.value.name)
    }
}
