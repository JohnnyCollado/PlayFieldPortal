package com.playfieldportal.core.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.ui.icons.CustomIcon
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour of the user's per-slot custom icon storage: `filesDir/custom-icons/<slotKey>.<ext>`
 * is the source of truth, mirroring how PfpThemeStore handles `theme-icons/`. Guards the
 * extension-swap rule (a slot holds ONE file — a new pick with a different extension must
 * remove the old file) and the Coil eviction on GIF replacement (path-keyed cache otherwise
 * keeps playing the old animation forever).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CustomIconStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class RecordingEvictor : CustomIconCacheEvictor {
        val evicted = mutableListOf<String>()
        override fun evict(path: String) {
            evicted += path
        }
    }

    private lateinit var evictor: RecordingEvictor
    private lateinit var store: CustomIconStore

    @Before
    fun setUp() {
        // The prefs DataStore and the icon dir persist within the test JVM; wipe both so each
        // case starts empty.
        runBlocking { context.pfpDataStore.edit { it.clear() } }
        File(context.filesDir, CustomIconStore.CUSTOM_ICONS_DIR).deleteRecursively()
        evictor = RecordingEvictor()
        store = CustomIconStore(context, evictor)
    }

    // ── import ────────────────────────────────────────────────────────────────

    @Test
    fun `import writes a slot-keyed file and bumps the stamp`() = runTest {
        val result = store.import("catbar_games", register(pngBytes()), "image/png")

        assertTrue(result.ok, result.message ?: "import rejected")
        val dest = iconFile("catbar_games", "png")
        assertTrue(dest.isFile, "stored as <slotKey>.<ext>, not a unique filename")
        assertNotNull(stampPref(), "import bumps the stamp so observers reload")
        assertEquals(listOf(dest.absolutePath), evictor.evicted, "still imports evict too — the slot may previously have held a GIF")
    }

    @Test
    fun `imported stills load as CustomIcon Still`() = runTest {
        store.import("catbar_games", register(pngBytes()), "image/png")

        val loaded = store.load()
        val icon = assertNotNull(loaded["catbar_games"], "the imported slot is present")
        assertIs<CustomIcon.Still>(icon)
    }

    @Test
    fun `re-importing with a different extension removes the old file and evicts`() = runTest {
        // First pick: PNG. Second pick: GIF (PNG bytes are fine — the gate probes dimensions,
        // not container structure). The slot must end up holding exactly one file.
        store.import("catbar_music", register(pngBytes()), "image/png")
        evictor.evicted.clear()

        val result = store.import("catbar_music", register(pngBytes()), "image/gif")

        assertTrue(result.ok, result.message ?: "gif import rejected")
        assertTrue(iconFile("catbar_music", "gif").isFile, "the new extension is stored")
        assertFalse(iconFile("catbar_music", "png").isFile, "the old-extension file must be removed")
        val evicted = iconFile("catbar_music", "gif").absolutePath
        assertTrue(evictor.evicted.contains(evicted), "a GIF read by Coil must be evicted or the old animation keeps playing")
    }

    @Test
    fun `invalid slot keys write nothing`() = runTest {
        for (key in listOf("not_a_slot", "../evil", "catbar_games/../../x", "", "sysicon_default")) {
            val result = store.import(key, register(pngBytes()), "image/png")
            assertFalse(result.ok, "key '$key' must be rejected")
        }
        assertTrue(iconDir().listFiles().isNullOrEmpty(), "no files written for invalid keys")
        assertNull(stampPref(), "no stamp bump without a successful import")
    }

    @Test
    fun `unsupported mime is rejected before any copy`() = runTest {
        val result = store.import("catbar_games", register(pngBytes()), "video/mp4")

        assertFalse(result.ok)
        assertTrue(iconDir().listFiles().isNullOrEmpty(), "rejected picks must not leave files behind")
    }

    @Test
    fun `oversized pick is rejected`() = runTest {
        val big = ByteArray(CustomIconLimits_BYTES.toInt() + 1)
        val result = store.import("catbar_games", register(big), "image/png")

        assertFalse(result.ok, "a file over the size cap must be rejected")
        assertTrue(iconDir().listFiles().isNullOrEmpty())
    }

    // ── load ──────────────────────────────────────────────────────────────────

    @Test
    fun `load skips unknown keys and unknown extensions`() = runTest {
        store.import("catbar_games", register(pngBytes()), "image/png")
        // Hostile/foreign files that could only arrive outside the store's own writes.
        iconFile("not_a_slot", "png").writeBytes(pngBytes())
        iconFile("catbar_music", "mp4").writeBytes(pngBytes())

        val loaded = store.load()

        assertEquals(setOf("catbar_games"), loaded.keys, "unknown slot keys and extensions are skipped, not crashed on")
    }

    // ── clear ─────────────────────────────────────────────────────────────────

    @Test
    fun `clear removes the slot file`() = runTest {
        store.import("status_bluetooth", register(pngBytes()), "image/png")
        store.import("catbar_games", register(pngBytes()), "image/png")

        store.clear("status_bluetooth")

        val loaded = store.load()
        assertNull(loaded["status_bluetooth"])
        assertNotNull(loaded["catbar_games"], "clear is per-slot — other slots untouched")
    }

    @Test
    fun `clearAll empties the directory`() = runTest {
        store.import("catbar_games", register(pngBytes()), "image/png")
        store.import("sysicon_snes", register(pngBytes()), "image/png")

        store.clearAll()

        assertTrue(store.load().isEmpty())
        assertTrue(iconDir().listFiles().isNullOrEmpty())
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun pngBytes(width: Int = 64, height: Int = 64): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun register(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://test/${System.nanoTime()}.png")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        return uri
    }

    private fun iconDir() = File(context.filesDir, CustomIconStore.CUSTOM_ICONS_DIR)
    private fun iconFile(slotKey: String, ext: String) = File(iconDir(), "$slotKey.$ext")

    private suspend fun stampPref(): Long? =
        context.pfpDataStore.data.first()[longPreferencesKey("custom_icons_stamp")]

    private companion object {
        // Mirror CustomIconLimits.MAX_BYTES by its string contract so the oversized test stays
        // honest about which cap it is exercising.
        const val CustomIconLimits_BYTES = 8L * 1024 * 1024
    }
}
