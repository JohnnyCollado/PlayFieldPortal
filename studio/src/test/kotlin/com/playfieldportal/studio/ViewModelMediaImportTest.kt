package com.playfieldportal.studio

import com.playfieldportal.studio.io.MediaGates
import com.playfieldportal.themekit.PfpThemeCodec
import com.playfieldportal.themekit.UiMediaLimits
import com.playfieldportal.themekit.WavFixtures
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class ViewModelMediaImportTest {

    private val dir: File = createTempDirectory("studio-vm-media").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private suspend fun StudioViewModel.awaitIdle() {
        withTimeout(30_000) {
            delay(50)
            while (state.value.busy) delay(25)
        }
    }

    private fun wav(name: String, ms: Long) = File(dir, name).also { MediaTestFiles.writeWav(it, ms) }

    @Test
    fun `an accepted sound lands as a scratch copy and is one undoable edit`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val src = wav("tap.wav", 300)
        vm.importSound("sound_scroll", src)
        vm.awaitIdle()
        val scratch = vm.state.value.mediaFiles["sound_scroll"]
        assertNotNull(scratch)
        assertTrue(scratch.isFile && scratch != src && scratch.extension == "wav")
        assertTrue(vm.canUndo.value)

        src.delete() // the author may move their original; the scratch copy is ours
        assertTrue(scratch.isFile)

        vm.undo()
        assertNull(vm.state.value.mediaFiles["sound_scroll"])
        assertTrue(scratch.isFile, "redo still needs the file")
        vm.redo()
        assertEquals(scratch, vm.state.value.mediaFiles["sound_scroll"])
        vm.newTheme()
        assertTrue(!scratch.exists(), "New releases the scratch file")
    }

    @Test
    fun `a float WAV imports as 16-bit PCM`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val src = File(dir, "deck_ui_navigation.wav").also {
            WavFixtures.writeSampleWav(it, 3, 32, 2, 44_100, WavFixtures.floats32(*FloatArray(4410 * 2) { 0.25f }), extraChunks = true)
        }
        vm.importSound("sound_scroll", src)
        vm.awaitIdle()
        assertNull(vm.state.value.dialog, "no error")
        val scratch = assertNotNull(vm.state.value.mediaFiles["sound_scroll"])
        val pcm = WavFixtures.readPcm16(scratch)
        assertEquals(1, pcm.formatTag)
        assertEquals(16, pcm.bits)
        assertEquals(4410 * 2, pcm.samples.size)
        assertTrue(pcm.samples.all { it == 8192.toShort() })
    }

    @Test
    fun `a rejected sound sets a by-name error and changes nothing`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        vm.importSound("sound_scroll", wav("long.wav", 900))
        vm.awaitIdle()
        assertEquals(
            StudioDialog.Error(UiMediaLimits.tooLong(UiMediaLimits.NAVIGATION)),
            vm.state.value.dialog,
        )
        assertTrue(vm.state.value.mediaFiles.isEmpty())
        assertTrue(!vm.canUndo.value)
    }

    @Test
    fun `importSound refuses a non-sound slot`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        vm.importSound("ambience_audio", wav("a.wav", 100))
        vm.awaitIdle()
        assertEquals(StudioDialog.Error(MediaGates.MSG_UNKNOWN_SLOT), vm.state.value.dialog)
        assertTrue(vm.state.value.mediaFiles.isEmpty())
    }

    @Test
    fun `ambience, boot and gameboot import under their slot keys`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val clip = File(dir, "clip.mp4").also { MotionTestMedia.writeTestMp4(it, 64, 48, 6) }
        vm.importAmbience(wav("amb.wav", 60_000))
        vm.awaitIdle()
        vm.importBoot(clip)
        vm.awaitIdle()
        vm.importGameBoot(clip)
        vm.awaitIdle()
        assertEquals(setOf("ambience_audio", "boot_video", "gameboot_video"), vm.state.value.mediaFiles.keys)
        assertNull(vm.state.value.dialog)
    }

    @Test
    fun `webm boot is rejected with the A10 reason`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        vm.importBoot(File(dir, "boot.webm").also { it.writeBytes(ByteArray(32)) })
        vm.awaitIdle()
        assertEquals(StudioDialog.Error(MediaGates.MSG_WEBM_NOT_AUTHORABLE), vm.state.value.dialog)
    }

    @Test
    fun `clearMedia is undoable and an imported sound exports`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        vm.importSound("sound_confirm", wav("c.wav", 800))
        vm.awaitIdle()
        val out = File(dir, "out.pfptheme")
        vm.exportTo(out) { null }
        vm.awaitIdle()
        val entry = PfpThemeCodec.read(out)?.media?.get("sound_confirm")
        assertNotNull(entry)
        assertEquals("wav", entry.extension)

        vm.clearMedia("sound_confirm")
        assertTrue(vm.state.value.mediaFiles.isEmpty())
        vm.undo()
        assertTrue(vm.state.value.mediaFiles.containsKey("sound_confirm"))
    }

    @Test
    fun `replacing a sound keeps the old file for undo`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        vm.importSound("sound_back", wav("one.wav", 100))
        vm.awaitIdle()
        val first = vm.state.value.mediaFiles.getValue("sound_back")
        vm.importSound("sound_back", wav("two.wav", 200))
        vm.awaitIdle()
        val second = vm.state.value.mediaFiles.getValue("sound_back")
        assertTrue(first != second && first.isFile && second.isFile)
        vm.undo()
        assertEquals(first, vm.state.value.mediaFiles["sound_back"])
    }

    @Test
    fun `exportCheck reflects the live state`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        vm.importSound("sound_scroll", wav("t.wav", 300))
        vm.awaitIdle()
        val check = vm.exportCheck()
        assertTrue(check.bytesByKind.getValue(BudgetKind.SOUNDS) > 0)
        assertTrue(check.canExport)
    }
}
