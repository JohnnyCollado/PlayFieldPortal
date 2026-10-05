package com.playfieldportal.studio

import com.playfieldportal.studio.io.BatchProgress
import com.playfieldportal.studio.io.UpgradeBatch
import com.playfieldportal.themekit.PfpThemeCodec
import com.playfieldportal.themekit.PfpThemeManifest
import com.playfieldportal.themekit.ThemeFixtures
import com.playfieldportal.themekit.consoleArt
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class UpgradeBatchTest {

    private val today = "2026-10-02"

    private fun withDir(block: (File) -> Unit) {
        val dir = createTempDirectory("studio-upgrade-batch").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun File.put(name: String, bytes: ByteArray) = File(this, name).also { it.writeBytes(bytes) }

    @Test
    fun `old themes are upgraded to v4 with a byte-identical bak`() = withDir { dir ->
        val originals = mapOf(
            "a-v1.pfptheme" to ThemeFixtures.v1(),
            "b-v2.pfptheme" to ThemeFixtures.v2(),
            "c-v3.pfptheme" to ThemeFixtures.v3(),
        )
        originals.forEach { (n, b) -> dir.put(n, b) }
        val progress = mutableListOf<BatchProgress>()

        val summary = UpgradeBatch.run(dir, today) { progress += it }

        assertEquals(originals.keys, summary.upgraded.map { it.name }.toSet())
        assertTrue(summary.failed.isEmpty() && summary.alreadyCurrent.isEmpty() && summary.newerVersion.isEmpty())
        for ((name, bytes) in originals) {
            val bak = File(dir, "$name.bak")
            assertContentEquals(bytes, bak.readBytes(), "$name: .bak must be byte-identical")
            val now = assertNotNull(PfpThemeCodec.read(File(dir, name)))
            assertEquals(PfpThemeManifest.SCHEMA_VERSION, now.manifest.schemaVersion)
            assertEquals(today, now.manifest.updated)
        }
        assertTrue(summary.upgraded.all { it.report.added.isNotEmpty() })
        assertEquals(BatchProgress(done = 3, total = 3, current = ""), progress.last())
        assertEquals(4, progress.size)
    }

    @Test
    fun `a v3 theme keeps its console icons through the batch`() = withDir { dir ->
        dir.put("v3.pfptheme", ThemeFixtures.v3())
        UpgradeBatch.run(dir, today)
        assertEquals(setOf("psx"), assertNotNull(PfpThemeCodec.read(File(dir, "v3.pfptheme"))).consoleArt.keys)
    }

    @Test
    fun `a corrupt file is reported and untouched`() = withDir { dir ->
        val junk = "this is not a zip".toByteArray()
        dir.put("broken.pfptheme", junk)
        dir.put("good.pfptheme", ThemeFixtures.v3())

        val summary = UpgradeBatch.run(dir, today)

        assertEquals(listOf("broken.pfptheme"), summary.failed.map { it.first })
        assertTrue(summary.failed.single().second.isNotBlank())
        assertContentEquals(junk, File(dir, "broken.pfptheme").readBytes())
        assertTrue(!File(dir, "broken.pfptheme.bak").exists(), "nothing to back up for an untouched file")
        assertEquals(listOf("good.pfptheme"), summary.upgraded.map { it.name })
    }

    @Test
    fun `a newer-than-known file is left alone and listed`() = withDir { dir ->
        val future = ThemeFixtures.future()
        dir.put("future.pfptheme", future)

        val summary = UpgradeBatch.run(dir, today)

        assertEquals(listOf("future.pfptheme"), summary.newerVersion)
        assertTrue(summary.upgraded.isEmpty())
        assertContentEquals(future, File(dir, "future.pfptheme").readBytes())
        assertTrue(!File(dir, "future.pfptheme.bak").exists())
    }

    @Test
    fun `re-running is a no-op`() = withDir { dir ->
        dir.put("v1.pfptheme", ThemeFixtures.v1())
        dir.put("v3.pfptheme", ThemeFixtures.v3())
        UpgradeBatch.run(dir, today)
        val after = dir.listFiles()!!.filter { it.extension == "pfptheme" }.associate { it.name to it.readBytes() }
        val baks = dir.listFiles()!!.filter { it.name.contains(".bak") }.map { it.name }.sorted()

        val again = UpgradeBatch.run(dir, today)

        assertTrue(again.upgraded.isEmpty() && again.failed.isEmpty())
        assertEquals(setOf("v1.pfptheme", "v3.pfptheme"), again.alreadyCurrent.toSet())
        for ((name, bytes) in after) assertContentEquals(bytes, File(dir, name).readBytes(), "$name unchanged")
        assertEquals(baks, dir.listFiles()!!.filter { it.name.contains(".bak") }.map { it.name }.sorted())
    }

    @Test
    fun `an existing bak is never overwritten`() = withDir { dir ->
        val old = "precious earlier backup".toByteArray()
        // One build: fixture zips carry entry timestamps, so two builds can differ.
        val original = ThemeFixtures.v3()
        dir.put("t.pfptheme", original)
        dir.put("t.pfptheme.bak", old)

        val summary = UpgradeBatch.run(dir, today)

        assertContentEquals(old, File(dir, "t.pfptheme.bak").readBytes())
        val item = summary.upgraded.single()
        assertNotNull(item.backup)
        assertTrue(item.backup.name != "t.pfptheme.bak")
        assertContentEquals(original, item.backup.readBytes())
    }

    @Test
    fun `motion media and passthrough survive the in-place rewrite`() = withDir { dir ->
        dir.put("v4.pfptheme", ThemeFixtures.v4())
        dir.put("v3.pfptheme", ThemeFixtures.v3())
        val before = assertNotNull(PfpThemeCodec.read(File(dir, "v4.pfptheme")))
        UpgradeBatch.run(dir, today)
        val after = assertNotNull(PfpThemeCodec.read(File(dir, "v4.pfptheme")))
        assertEquals(before.media.keys, after.media.keys)
        assertEquals(before.motion?.extension, after.motion?.extension)
    }

    @Test
    fun `the report file lists every outcome and is never overwritten`() = withDir { dir ->
        dir.put("old.pfptheme", ThemeFixtures.v2())
        dir.put("bad.pfptheme", "nope".toByteArray())
        dir.put("future.pfptheme", ThemeFixtures.future())

        val summary = UpgradeBatch.run(dir, today)
        val report = assertNotNull(summary.reportFile)
        val text = report.readText()
        assertTrue("old.pfptheme" in text && "bad.pfptheme" in text && "future.pfptheme" in text)
        assertTrue(text.contains("Upgraded") && text.contains("Failed") && text.contains("newer"))

        // A second run with new work writes a second report; the first stays.
        dir.put("old2.pfptheme", ThemeFixtures.v1())
        val second = assertNotNull(UpgradeBatch.run(dir, today).reportFile)
        assertTrue(second != report && report.readText() == text)
    }

    @Test
    fun `a run that changes nothing writes no report`() = withDir { dir ->
        dir.put("v1.pfptheme", ThemeFixtures.v1())
        UpgradeBatch.run(dir, today)
        assertNull(UpgradeBatch.run(dir, today).reportFile)
    }

    @Test
    fun `the view model batch publishes progress then a summary dialog`() = runBlocking {
        val dir = createTempDirectory("studio-upgrade-vm").toFile()
        try {
            dir.put("v1.pfptheme", ThemeFixtures.v1())
            val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
            vm.upgradeFolder(dir)
            withTimeout(30_000) {
                delay(50)
                while (vm.state.value.busy) delay(25)
            }
            val dialog = vm.state.value.dialog
            assertTrue(dialog is StudioDialog.UpgradeDone, "got $dialog")
            assertEquals(listOf("v1.pfptheme"), dialog.summary.upgraded.map { it.name })
            assertNull(vm.state.value.batchProgress)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `banner state follows the opened schema`() {
        val report = com.playfieldportal.themekit.UpgradeReport(emptyList(), emptyList(), emptyList(), emptyList())
        val current = com.playfieldportal.themekit.PfpThemeManifest.SCHEMA_VERSION
        val added = report.copy(added = listOf("Format version $current (was 3)"))
        assertEquals(UpgradeBanner.None, StudioState().upgradeBanner)
        assertEquals(UpgradeBanner.None, StudioState(schemaVersion = current, upgradeReport = report).upgradeBanner)
        assertEquals(
            UpgradeBanner.Available(added),
            StudioState(schemaVersion = 3, upgradeReport = added).upgradeBanner,
        )
        assertEquals(UpgradeBanner.NewerVersion(99), StudioState(schemaVersion = 99, upgradeReport = added).upgradeBanner)
        assertTrue(StudioState(schemaVersion = 3, upgradeReport = added).upgradeAvailable)
        assertTrue(!StudioState(schemaVersion = 99, upgradeReport = added).upgradeAvailable)
    }
}
