package com.playfieldportal.feature.launcher.kb

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.data.kb.EmulatorKbDownloader
import com.playfieldportal.core.data.kb.KbDownload
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/** The official update pipeline. Fixtures are signed by the JDK's Ed25519. */
@RunWith(RobolectricTestRunner::class)
class EmulatorKbUpdaterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.filesDir, "emulator_kb")
    private val officialFile get() = File(root, "official/emulators.json")
    private val stateFile get() = File(root, "official/state.json")

    private val keys: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val downloader = mockk<EmulatorKbDownloader>()
    private val refresher = mockk<EmulatorKnowledgeRefresher>(relaxed = true)

    private var now = 1_000_000_000_000L
    private var appVersion = 10

    // What the fake server serves.
    private var manifest: KbDownload = KbDownload.Failure("unset")
    private var signature: KbDownload = KbDownload.Failure("unset")

    @Before
    fun clean() = runBlocking<Unit> {
        root.deleteRecursively()
        context.pfpDataStore.edit { it.clear() }
        coEvery { downloader.fetch(EmulatorKbDownloader.MANIFEST_URL, any()) } answers { manifest }
        coEvery { downloader.fetch(EmulatorKbDownloader.SIGNATURE_URL, any()) } answers { signature }
    }

    private fun rawPublicKey(pair: KeyPair): ByteArray = pair.public.encoded.takeLast(32).toByteArray()

    private fun sign(pair: KeyPair, bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(
            Signature.getInstance("Ed25519").run {
                initSign(pair.private)
                update(bytes)
                sign()
            },
        )

    private fun newUpdater(pinned: List<ByteArray> = listOf(rawPublicKey(keys))) = EmulatorKbUpdater(
        context = context,
        downloader = downloader,
        store = EmulatorKnowledgeStore(context, Dispatchers.Unconfined),
        refresher = refresher,
        verifier = KbSignatureVerifier(pinned),
        clock = { now },
        appVersion = { appVersion },
    )

    private fun kbText(
        version: Long,
        schema: Int = 1,
        minApp: Int = 0,
        launch: String = """{"intentType":"ACTION_VIEW","action":"android.intent.action.VIEW"}""",
    ) = """
        {"format":"pfp-emulator-kb","schemaVersion":$schema,"version":$version,"label":"v$version","minAppVersion":$minApp,
         "emulators":[{"id":"upd_emu","name":"Updated","packageNames":["com.example.updated"],"platformIds":["psx"],"launch":$launch}],
         "platforms":[{"id":"psx","romExtensions":["abc"]}]}
    """.trimIndent()

    /** Serve [text], signed by [signer]. */
    private fun serve(text: String, signer: KeyPair = keys) {
        val bytes = text.toByteArray()
        manifest = KbDownload.Bytes(bytes)
        signature = KbDownload.Bytes(sign(signer, bytes).toByteArray())
    }

    private fun status() = runBlocking { newUpdater().status.first() }

    private fun fetchCalls(exactly: Int) =
        coVerify(exactly = exactly) { downloader.fetch(any(), any()) }

    @Test
    fun `a signed valid newer file installs and the refresher runs once`() = runBlocking<Unit> {
        val text = kbText(2026100200)
        serve(text)
        assertEquals(KbUpdateResult.Installed, newUpdater().check(manual = true))

        assertArrayEquals(text.toByteArray(), officialFile.readBytes())
        coVerify(exactly = 1) { refresher.run() }
        val s = status()!!
        assertEquals(KbUpdateResult.Installed, s.result)
        assertEquals(2026100200L, s.version)
        assertEquals("v2026100200", s.label)
        assertEquals(1, s.emulatorCount)
        assertEquals(1, s.platformCount)
        assertTrue(stateFile.readText().contains("2026100200"))
    }

    @Test
    fun `a bad signature installs nothing`() = runBlocking<Unit> {
        serve(kbText(2026100200), signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair())
        assertEquals(KbUpdateResult.SignatureInvalid, newUpdater().check(manual = true))
        assertFalse(officialFile.exists())
        coVerify(exactly = 0) { refresher.run() }
        assertEquals(KbUpdateResult.SignatureInvalid, status()!!.result)
    }

    @Test
    fun `the signature is checked before the body is parsed`() = runBlocking<Unit> {
        serve("not json at all", signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair())
        assertEquals(KbUpdateResult.SignatureInvalid, newUpdater().check(manual = true))

        serve("not json at all")
        assertEquals(KbUpdateResult.InvalidContent, newUpdater().check(manual = true))
    }

    @Test
    fun `a newer schema needs an app update`() = runBlocking<Unit> {
        serve(kbText(2026100200, schema = 2))
        assertEquals(KbUpdateResult.NeedsAppUpdate, newUpdater().check(manual = true))
        assertFalse(officialFile.exists())
        coVerify(exactly = 0) { refresher.run() }
    }

    @Test
    fun `a newer schema with unknown top-level keys still needs an app update`() = runBlocking<Unit> {
        serve("""{"format":"pfp-emulator-kb","schemaVersion":2,"version":5,"futureField":1,"emulators":[]}""")
        assertEquals(KbUpdateResult.NeedsAppUpdate, newUpdater().check(manual = true))
    }

    @Test
    fun `a minAppVersion above the running app needs an app update`() = runBlocking<Unit> {
        serve(kbText(2026100200, minApp = 11))
        assertEquals(KbUpdateResult.NeedsAppUpdate, newUpdater().check(manual = true))
        assertFalse(officialFile.exists())
    }

    @Test
    fun `a minAppVersion equal to the running app installs`() = runBlocking<Unit> {
        serve(kbText(2026100200, minApp = 10))
        assertEquals(KbUpdateResult.Installed, newUpdater().check(manual = true))
    }

    @Test
    fun `a lower version than the highest accepted keeps the installed file`() = runBlocking<Unit> {
        val first = kbText(2026100300)
        serve(first)
        assertEquals(KbUpdateResult.Installed, newUpdater().check(manual = true))

        serve(kbText(2026100200))
        assertEquals(KbUpdateResult.OlderThanInstalled, newUpdater().check(manual = true))
        assertArrayEquals(first.toByteArray(), officialFile.readBytes())
        coVerify(exactly = 1) { refresher.run() }
        // The failure keeps what is installed in the status.
        val s = status()!!
        assertEquals(KbUpdateResult.OlderThanInstalled, s.result)
        assertEquals(2026100300L, s.version)
    }

    @Test
    fun `the highest version survives a reset`() = runBlocking<Unit> {
        serve(kbText(2026100300))
        newUpdater().check(manual = true)
        EmulatorKnowledgeStore(context, Dispatchers.Unconfined).resetToBuiltIn()
        assertFalse(officialFile.exists())

        serve(kbText(2026100200))
        assertEquals(KbUpdateResult.OlderThanInstalled, newUpdater().check(manual = true))
        assertFalse(officialFile.exists())
    }

    @Test
    fun `an equal version after a reset re-installs`() = runBlocking<Unit> {
        serve(kbText(2026100300))
        assertEquals(KbUpdateResult.Installed, newUpdater().check(manual = true))
        EmulatorKnowledgeStore(context, Dispatchers.Unconfined).resetToBuiltIn()
        assertFalse(officialFile.exists())

        assertEquals(KbUpdateResult.Installed, newUpdater().check(manual = true))
        assertTrue(officialFile.exists())
        coVerify(exactly = 2) { refresher.run() }
    }

    @Test
    fun `one invalid entry installs nothing`() = runBlocking<Unit> {
        serve(kbText(2026100200, launch = """{"intentType":"CUSTOM_COMMAND"}"""))
        assertEquals(KbUpdateResult.InvalidContent, newUpdater().check(manual = true))
        assertFalse(officialFile.exists())
        coVerify(exactly = 0) { refresher.run() }
        assertEquals(KbUpdateResult.InvalidContent, status()!!.result)
    }

    @Test
    fun `a download failure is offline and a big one is too large`() = runBlocking<Unit> {
        manifest = KbDownload.Failure("UnknownHostException")
        assertEquals(KbUpdateResult.Offline, newUpdater().check(manual = true))

        manifest = KbDownload.Failure("too large")
        assertEquals(KbUpdateResult.TooLarge, newUpdater().check(manual = true))

        serve(kbText(2026100200))
        signature = KbDownload.Failure("http 404")
        assertEquals(KbUpdateResult.Offline, newUpdater().check(manual = true))
        assertFalse(officialFile.exists())
    }

    @Test
    fun `an offline check does not start the 24 hour wait`() = runBlocking<Unit> {
        manifest = KbDownload.Failure("UnknownHostException")
        val updater = newUpdater()
        assertEquals(KbUpdateResult.Offline, updater.check(manual = false))
        assertEquals(0L, updater.lastCheckAt.first())

        serve(kbText(2026100200))
        assertEquals(KbUpdateResult.Installed, updater.check(manual = false))
    }

    @Test
    fun `an automatic check within 24 hours makes no network call`() = runBlocking<Unit> {
        serve(kbText(2026100200))
        assertEquals(KbUpdateResult.Installed, newUpdater().check(manual = false))
        fetchCalls(exactly = 2)

        now += 23L * 3_600_000
        assertEquals(KbUpdateResult.Skipped, newUpdater().check(manual = false))
        fetchCalls(exactly = 2)

        now += 2L * 3_600_000
        assertEquals(KbUpdateResult.Installed, newUpdater().check(manual = false))
        fetchCalls(exactly = 4)
    }

    @Test
    fun `an automatic check with the toggle off makes no network call`() = runBlocking<Unit> {
        serve(kbText(2026100200))
        val updater = newUpdater()
        updater.setAutoUpdate(false)
        assertFalse(updater.autoUpdateEnabled.first())
        assertEquals(KbUpdateResult.Skipped, updater.check(manual = false))
        fetchCalls(exactly = 0)
        assertNull(updater.status.first())
    }

    @Test
    fun `the toggle defaults to on`() = runBlocking<Unit> {
        assertTrue(newUpdater().autoUpdateEnabled.first())
    }

    @Test
    fun `a manual check bypasses the toggle and the throttle`() = runBlocking<Unit> {
        serve(kbText(2026100200))
        val updater = newUpdater()
        updater.setAutoUpdate(false)
        assertEquals(KbUpdateResult.Installed, updater.check(manual = true))

        updater.setAutoUpdate(true)
        // Just checked, so an automatic one is throttled; a manual one still goes out.
        assertEquals(KbUpdateResult.Skipped, updater.check(manual = false))
        assertEquals(KbUpdateResult.Installed, updater.check(manual = true))
        fetchCalls(exactly = 4)
        assertEquals(now, updater.lastCheckAt.first())
    }

    @Test
    fun `an empty pinned list is not configured and makes no network call`() = runBlocking<Unit> {
        serve(kbText(2026100200))
        val updater = newUpdater(pinned = emptyList())
        assertEquals(KbUpdateResult.NotConfigured, updater.check(manual = true))
        assertEquals(KbUpdateResult.NotConfigured, updater.check(manual = false))
        fetchCalls(exactly = 0)
        assertFalse(officialFile.exists())
        assertNotNull(updater.status.first())
        assertEquals(KbUpdateResult.NotConfigured, updater.status.first()!!.result)
    }

    @Test
    fun `a file equal to the built-in KB is older than installed`() = runBlocking<Unit> {
        val builtInVersion = runBlocking { EmulatorKnowledgeStore(context, Dispatchers.Unconfined).builtInVersion() }
        serve(kbText(builtInVersion))
        assertEquals(KbUpdateResult.OlderThanInstalled, newUpdater().check(manual = true))
        assertFalse(officialFile.exists())
    }

    @Test
    fun `a failed state write installs nothing`() = runBlocking<Unit> {
        // A non-empty directory where the temp file belongs makes the state write fail.
        File(stateFile.parentFile, "state.json.tmp").apply { mkdirs() }.resolve("keep").writeText("x")
        serve(kbText(2026100200))
        assertEquals(KbUpdateResult.StorageFailed, newUpdater().check(manual = true))
        assertFalse(officialFile.exists())
        coVerify(exactly = 0) { refresher.run() }
    }

    @Test
    fun `isConfigured follows the pinned keys`() {
        assertTrue(newUpdater().isConfigured)
        assertFalse(newUpdater(pinned = emptyList()).isConfigured)
    }
}
