package com.playfieldportal.studio

import java.io.File
import kotlin.io.path.createTempDirectory
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

/**
 * The undo history owns scratch-file lifetime: a file is deleted only when neither the live state
 * nor any reachable snapshot (past or future) references it.
 */
class EditHistoryTest {

    private fun withDir(block: (File) -> Unit) {
        val dir = createTempDirectory("studio-history").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun scratch(dir: File, name: String) = File(dir, name).also { it.writeText(name) }

    private fun withMotion(file: File?, name: String = "x") = StudioState(name = name, motionFile = file)

    @Test
    fun `undo and redo walk the recorded edits`() {
        val h = EditHistory()
        val s0 = StudioState(name = "a")
        val s1 = StudioState(name = "b")
        assertFalse(h.canUndo)
        h.record(s0, s1, key = null)
        assertTrue(h.canUndo)
        assertFalse(h.canRedo)

        assertEquals("a", h.undo(s1)?.name)
        assertFalse(h.canUndo)
        assertTrue(h.canRedo)
        assertEquals("b", h.redo(s0)?.name)
        assertNull(h.redo(s1), "nothing left to redo")
    }

    @Test
    fun `an edit that changes nothing in the document is not recorded`() {
        val h = EditHistory()
        val s = StudioState(name = "a")
        h.record(s, s.copy(statusMessage = "hi", busy = true), key = null)
        assertFalse(h.canUndo)
    }

    @Test
    fun `a new edit after undo clears redo and frees files only the future held`() = withDir { dir ->
        val f = scratch(dir, "future.mp4")
        val h = EditHistory()
        val base = withMotion(null)
        val withFile = withMotion(f)
        h.record(base, withFile, null)
        val undone = assertNotNull(h.undo(withFile))
        assertTrue(f.exists(), "the redo branch still references it")

        h.record(undone, undone.copy(name = "different"), null)
        assertFalse(h.canRedo)
        assertFalse(f.exists(), "unreachable once redo is cleared")
    }

    @Test
    fun `clearing motion then undoing keeps the file alive`() = withDir { dir ->
        val f = scratch(dir, "motion.mp4")
        val h = EditHistory()
        val before = withMotion(f)
        val after = withMotion(null)
        h.record(before, after, null)
        assertTrue(f.exists(), "an undo can still reach it")
        val restored = assertNotNull(h.undo(after))
        assertEquals(f, restored.motionFile)
        assertTrue(f.exists())
    }

    @Test
    fun `trimming history deletes files no snapshot references`() = withDir { dir ->
        val files = (0..3).map { scratch(dir, "m$it.mp4") }
        val h = EditHistory(limit = 2)
        var live = withMotion(files[0])
        for (i in 1..3) {
            val next = withMotion(files[i])
            h.record(live, next, null)
            live = next
        }
        // past = [m1, m2], live = m3: m0 fell off the front.
        assertFalse(files[0].exists(), "trimmed out of history")
        assertTrue(files.drop(1).all { it.exists() })
    }

    @Test
    fun `a file still in the live state survives even when history forgets it`() = withDir { dir ->
        val f = scratch(dir, "kept.mp4")
        val h = EditHistory(limit = 1)
        var live = withMotion(f)
        repeat(3) { i ->
            val next = live.copy(name = "n$i")
            h.record(live, next, null)
            live = next
        }
        assertTrue(f.exists())
    }

    @Test
    fun `reset deletes outgoing and history files but keeps the incoming ones`() = withDir { dir ->
        val old = scratch(dir, "old.mp4")
        val mid = scratch(dir, "mid.mp4")
        val incoming = scratch(dir, "incoming.mp4")
        val h = EditHistory()
        val a = withMotion(old)
        val b = withMotion(mid)
        h.record(a, b, null)
        h.reset(outgoing = b, incoming = withMotion(incoming))
        assertFalse(old.exists())
        assertFalse(mid.exists())
        assertTrue(incoming.exists())
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
    }

    @Test
    fun `same-key edits inside the gap coalesce into one undo step`() {
        var now = 0L
        val h = EditHistory(clock = { now })
        var live = StudioState(name = "start")
        for (i in 1..5) {
            val next = live.copy(name = "drag$i")
            h.record(live, next, key = "slider")
            live = next
            now += 100
        }
        assertEquals("start", h.undo(live)?.name)
        assertFalse(h.canUndo, "five drags were one step")
    }

    @Test
    fun `a pause or a different key starts a new undo step`() {
        var now = 0L
        val h = EditHistory(clock = { now })
        var live = StudioState(name = "start")
        fun edit(name: String, key: String?) {
            val next = live.copy(name = name)
            h.record(live, next, key)
            live = next
        }
        edit("one", "slider")
        now += EditHistory.COALESCE_GAP_MS + 1
        edit("two", "slider")
        edit("three", "other")
        assertEquals("two", h.undo(live)?.name)
        assertEquals("one", h.undo(live.copy(name = "two"))?.name)
        assertEquals("start", h.undo(live.copy(name = "one"))?.name)
        assertFalse(h.canUndo)
    }

    @Test
    fun `depth is bounded`() {
        val h = EditHistory(limit = 3)
        var live = StudioState(name = "0")
        for (i in 1..10) {
            val next = live.copy(name = "$i")
            h.record(live, next, null)
            live = next
        }
        var steps = 0
        while (h.canUndo) {
            live = h.undo(live)!!
            steps++
        }
        assertEquals(3, steps)
    }

    // ── Through the real ViewModel ───────────────────────────────────────────

    private suspend fun StudioViewModel.awaitIdle() {
        withTimeout(30_000) {
            delay(50)
            while (state.value.busy) delay(25)
        }
    }

    @Test
    fun `viewmodel clear motion then undo restores a file that still exports`() = runBlocking {
        val dir = createTempDirectory("studio-history-vm").toFile()
        try {
            val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
            val video = File(dir, "clip.mp4").also { MotionTestMedia.writeTestMp4(it) }
            vm.importVideo(video)
            vm.awaitIdle()
            vm.confirmWallpaper(WallpaperPreset.ORIGINAL)
            vm.awaitIdle()
            val motion = assertNotNull(vm.state.value.motionFile)

            vm.clearMotion()
            assertNull(vm.state.value.motionFile)
            assertTrue(motion.exists(), "history still references it")
            assertTrue(vm.canUndo.value)

            vm.undo()
            assertEquals(motion, vm.state.value.motionFile)
            assertTrue(motion.isFile)
            val out = File(dir, "out.pfptheme")
            vm.exportTo(out) { null }
            vm.awaitIdle()
            val exported = assertNotNull(com.playfieldportal.themekit.PfpThemeCodec.read(out)?.motion)
            assertEquals("mp4", exported.extension)

            vm.redo()
            assertNull(vm.state.value.motionFile)
            assertTrue(motion.exists(), "redo does not free what an undo can still reach")

            vm.newTheme()
            assertFalse(motion.exists(), "New releases every scratch file")
            assertFalse(vm.canUndo.value)
            assertFalse(vm.canRedo.value)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `viewmodel undo restores the edit and drops redo on a new edit`() {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        vm.setAccent(0xFF112233.toInt())
        vm.undo()
        assertEquals(com.playfieldportal.studio.io.PtfConversion.DEFAULT_ACCENT, vm.state.value.accentArgb)
        assertTrue(vm.canRedo.value)
        vm.setIconColor(IconColorChoice.Custom(0xFF00FF00.toInt()))
        assertFalse(vm.canRedo.value)
    }
}
