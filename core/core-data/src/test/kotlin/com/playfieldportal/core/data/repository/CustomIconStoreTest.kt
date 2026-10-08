package com.playfieldportal.core.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.ui.icons.CustomIcon
import com.playfieldportal.core.ui.icons.CustomIconLimits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import com.playfieldportal.core.ui.icons.UserCategoryIconKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
    private val tiers by lazy { ThemeTiers(context.filesDir) }

    /** The user tier as the XMB loads it. */
    private suspend fun loadPicks() = tiers.loadIcons(ThemeTiers.Tier.USER)

    @Before
    fun setUp() {
        // The prefs DataStore and the icon dir persist within the test JVM; wipe both so each
        // case starts empty.
        runBlocking { context.pfpDataStore.edit { it.clear() } }
        File(context.filesDir, CustomIconStore.CUSTOM_ICONS_DIR).deleteRecursively()
        evictor = RecordingEvictor()
        store = CustomIconStore(context, evictor, tiers)
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
    fun `a theme-tier file can be copied to the user tier through a file uri`() = runTest {
        // The grid pick (D6/D10): the source is a file we enumerated in theme-icons/, passed to the
        // ordinary import as file://, so the whole gate runs unchanged.
        val source = File(File(context.filesDir, PfpThemeStore.THEME_ICONS_DIR).apply { mkdirs() }, "catbar_games.png")
            .apply { writeBytes(pngBytes()) }

        val result = store.import("catbar_music", Uri.fromFile(source), "image/png")

        assertTrue(result.ok, result.message ?: "import rejected")
        assertTrue(iconFile("catbar_music", "png").isFile, "landed in custom-icons/ under the focused slot")
        assertTrue(source.isFile, "the theme-tier source is left alone")
        assertNotNull(stampPref())
        assertEquals(setOf("catbar_music"), loadPicks().keys)
    }

    @Test
    fun `imported stills load as CustomIcon Still`() = runTest {
        store.import("catbar_games", register(pngBytes()), "image/png")

        val loaded = loadPicks()
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
        for (key in listOf(
            "not_a_slot", "../evil", "catbar_games/../../x", "", "sysicon_not_a_console",
            "usercat_games", "usercat_custom_../x", "usercat_", "usercat_custom_A",
        )) {
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

    // ── user category key family ──────────────────────────────────────────────

    @Test
    fun `a usercat key imports, bumps the stamp, evicts and loads as Still`() = runTest {
        val result = store.import("usercat_custom_x_1", register(pngBytes()), "image/png")

        assertTrue(result.ok, result.message ?: "import rejected")
        val dest = iconFile("usercat_custom_x_1", "png")
        assertTrue(dest.isFile)
        assertNotNull(stampPref())
        assertEquals(listOf(dest.absolutePath), evictor.evicted)
        assertIs<CustomIcon.Still>(assertNotNull(loadPicks()["usercat_custom_x_1"]))
    }

    @Test
    fun `a rejected re-import to a usercat key leaves the old file untouched`() = runTest {
        store.import("usercat_custom_x_1", register(pngBytes()), "image/png")
        val old = iconFile("usercat_custom_x_1", "png")
        val before = old.readBytes()

        val result = store.import("usercat_custom_x_1", register(pngBytes()), "video/mp4")

        assertFalse(result.ok)
        assertEquals(CustomIconLimits.MSG_UNSUPPORTED_FORMAT, result.message)
        assertTrue(old.isFile && old.readBytes().contentEquals(before), "the previous image survives a rejection")
    }

    @Test
    fun `heic is accepted alongside heif`() = runTest {
        // PNG bytes decode fine; the point is that the MIME maps to a stored extension rather
        // than being refused as unsupported.
        val result = store.import("catbar_games", register(pngBytes()), "image/heic")

        assertTrue(result.ok, result.message ?: "heic import rejected")
        assertTrue(iconFile("catbar_games", "heif").isFile, "heic is stored under the heif suffix")
    }

    // ── load ──────────────────────────────────────────────────────────────────

    @Test
    fun `load skips unknown keys and unknown extensions`() = runTest {
        store.import("catbar_games", register(pngBytes()), "image/png")
        // Hostile/foreign files that could only arrive outside the store's own writes.
        iconFile("not_a_slot", "png").writeBytes(pngBytes())
        iconFile("catbar_music", "mp4").writeBytes(pngBytes())

        val loaded = loadPicks()

        assertEquals(setOf("catbar_games"), loaded.keys, "unknown slot keys and extensions are skipped, not crashed on")
    }

    // ── clear ─────────────────────────────────────────────────────────────────

    @Test
    fun `clear removes the slot file`() = runTest {
        store.import("status_bluetooth", register(pngBytes()), "image/png")
        store.import("catbar_games", register(pngBytes()), "image/png")

        assertTrue(store.clear("status_bluetooth"), "a stored pick reports as removed")

        val loaded = loadPicks()
        assertNull(loaded["status_bluetooth"])
        assertNotNull(loaded["catbar_games"], "clear is per-slot — other slots untouched")
    }

    // The overlay greys its Reset control and explains itself off these two returns: this tier
    // holds only user picks, so a slot the user never picked has nothing to clear even when an
    // icon is plainly on screen (the applied theme's, or the built-in).
    @Test
    fun `clear reports false when the slot has no user pick`() = runTest {
        assertFalse(store.clear("catbar_games"), "no pick stored — nothing was removed")
        assertFalse(store.clear("not_a_slot"), "an unknown key removes nothing")
    }

    @Test
    fun `clearAll empties the directory`() = runTest {
        store.import("catbar_games", register(pngBytes()), "image/png")
        store.import("sysicon_snes", register(pngBytes()), "image/png")

        assertTrue(store.clearAll(), "stored picks report as cleared")

        assertTrue(loadPicks().isEmpty())
        assertTrue(iconDir().listFiles().isNullOrEmpty())
    }

    @Test
    fun `clearAll reports false when nothing was stored`() = runTest {
        assertFalse(store.clearAll(), "no picks stored — nothing was cleared")
    }

    // ── move ──────────────────────────────────────────────────────────────────

    @Test
    fun `move renames the draft onto the category key, evicts and bumps the stamp`() = runTest {
        store.import(UserCategoryIconKeys.DRAFT_KEY, register(pngBytes()), "image/png")
        runBlocking { context.pfpDataStore.edit { it.clear() } }
        evictor.evicted.clear()

        assertTrue(store.move(UserCategoryIconKeys.DRAFT_KEY, "usercat_custom_x_1"))

        val dest = iconFile("usercat_custom_x_1", "png")
        assertTrue(dest.isFile, "the image now lives under the category's key")
        assertFalse(iconFile(UserCategoryIconKeys.DRAFT_KEY, "png").exists(), "the draft is gone")
        assertEquals(listOf(dest.absolutePath), evictor.evicted, "dest path evicted so a stale GIF can't keep playing")
        assertNotNull(stampPref(), "move bumps the stamp so observers reload")
    }

    @Test
    fun `move replaces a destination held under another extension`() = runTest {
        store.import("usercat_custom_x_1", register(pngBytes()), "image/png")
        store.import(UserCategoryIconKeys.DRAFT_KEY, register(pngBytes()), "image/gif")

        assertTrue(store.move(UserCategoryIconKeys.DRAFT_KEY, "usercat_custom_x_1"))

        assertTrue(iconFile("usercat_custom_x_1", "gif").isFile)
        assertFalse(iconFile("usercat_custom_x_1", "png").exists(), "a key holds ONE file")
    }

    @Test
    fun `move without a source writes nothing`() = runTest {
        assertFalse(store.move(UserCategoryIconKeys.DRAFT_KEY, "usercat_custom_x_1"))

        assertTrue(iconDir().listFiles().isNullOrEmpty())
        assertNull(stampPref(), "no bump without a real move")
    }

    @Test
    fun `move to an invalid key keeps the draft`() = runTest {
        store.import(UserCategoryIconKeys.DRAFT_KEY, register(pngBytes()), "image/png")
        runBlocking { context.pfpDataStore.edit { it.clear() } }

        assertFalse(store.move(UserCategoryIconKeys.DRAFT_KEY, "usercat_custom_../x"))
        assertFalse(store.move("../evil", "usercat_custom_x_1"))

        assertTrue(iconFile(UserCategoryIconKeys.DRAFT_KEY, "png").isFile, "the draft survives a refused move")
        assertNull(stampPref())
    }

    // ── pruneUserCategoryIcons ────────────────────────────────────────────────

    @Test
    fun `prune removes orphaned category images and the draft, keeps live and non-category picks`() = runTest {
        store.import("usercat_custom_keep_1", register(pngBytes()), "image/png")
        store.import("usercat_custom_gone_2", register(pngBytes()), "image/gif")
        store.import(UserCategoryIconKeys.DRAFT_KEY, register(pngBytes()), "image/png")
        store.import("catbar_games", register(pngBytes()), "image/png")
        store.import("sysicon_snes", register(pngBytes()), "image/png")
        // A malformed usercat file that could only arrive outside the store's own writes.
        iconFile("usercat_custom_A", "png").writeBytes(pngBytes())

        assertTrue(store.pruneUserCategoryIcons(setOf("custom_keep_1")))

        assertTrue(iconFile("usercat_custom_keep_1", "png").isFile)
        assertFalse(iconFile("usercat_custom_gone_2", "gif").exists())
        assertFalse(iconFile(UserCategoryIconKeys.DRAFT_KEY, "png").exists())
        assertFalse(iconFile("usercat_custom_A", "png").exists())
        assertTrue(iconFile("catbar_games", "png").isFile, "theme-slot picks are never swept")
        assertTrue(iconFile("sysicon_snes", "png").isFile, "console-slot picks are never swept")
    }

    @Test
    fun `prune bumps the stamp only when something was removed`() = runTest {
        store.import("usercat_custom_gone_2", register(pngBytes()), "image/png")
        runBlocking { context.pfpDataStore.edit { it.clear() } }

        assertTrue(store.pruneUserCategoryIcons(emptySet()))
        assertNotNull(stampPref(), "a real removal bumps the stamp")

        runBlocking { context.pfpDataStore.edit { it.clear() } }
        assertFalse(store.pruneUserCategoryIcons(emptySet()), "nothing left to remove")
        assertNull(stampPref(), "no bump without a removal")
    }

    // ── observeStoredKeys ─────────────────────────────────────────────────────

    @Test
    fun `observeStoredKeys lists storable keys only`() = runTest {
        store.import("catbar_games", register(pngBytes()), "image/png")
        store.import("usercat_custom_x_1", register(pngBytes()), "image/png")
        iconFile("not_a_slot", "png").writeBytes(pngBytes())
        iconFile("catbar_music", "mp4").writeBytes(pngBytes())

        assertEquals(setOf("catbar_games", "usercat_custom_x_1"), store.observeStoredKeys().first())
    }

    @Test
    fun `observeStoredKeys emits again after an import and after a clear`() = runBlocking {
        val seen = java.util.Collections.synchronizedList(mutableListOf<Set<String>>())
        val job = launch(Dispatchers.Default) { store.observeStoredKeys().collect { seen += it } }
        suspend fun awaitLast(expected: Set<String>) = withTimeout(10_000) {
            while (seen.lastOrNull() != expected) delay(20)
        }
        awaitLast(emptySet())

        store.import("usercat_custom_x_1", register(pngBytes()), "image/png")
        awaitLast(setOf("usercat_custom_x_1"))

        store.clear("usercat_custom_x_1")
        awaitLast(emptySet())
        job.cancel()
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
