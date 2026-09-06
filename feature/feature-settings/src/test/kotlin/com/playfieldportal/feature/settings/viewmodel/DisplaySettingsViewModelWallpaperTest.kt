package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.datastore.pfpDataStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Wallpaper pref contract for the Display settings importer — mirrors PfpThemeStoreTest's
 * cascade-pref contract tests.
 *
 * The motion-wallpaper invariant under test: `display_motion_wallpaper` is never set without
 * `display_custom_wallpaper`, and clearing the wallpaper clears BOTH keys — a leftover motion
 * path with a cleared poster is the unrenderable state the plan forbids.
 *
 * Concurrency note: the ViewModel launches on [dispatcher] (Dispatchers.setMain) while some of
 * its side effects complete on REAL threads (DataStore writes, Dispatchers.IO file deletes).
 * The wait helper therefore alternates `advanceUntilIdle()` (drives everything queued on the
 * test scheduler) with a real-thread sleep that never blocks the scheduler thread — blocking it
 * would deadlock the very coroutines we're waiting for.
 *
 * Coverage note: the video-probe success path (poster extraction through MediaMetadataRetriever)
 * is not exercised here — Robolectric can't decode a real MP4 on the JVM. Video import and the
 * decoder-release discipline are on-device checks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DisplaySettingsViewModelWallpaperTest {

    private val dispatcher = StandardTestDispatcher()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var vm: DisplaySettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // Synchronous clear — DataStore edits complete on real threads, no scheduler needed.
        runBlocking { context.pfpDataStore.edit { it.clear() } }
        File(context.filesDir, "wallpaper").deleteRecursively()
        vm = DisplaySettingsViewModel(context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        File(context.filesDir, "wallpaper").deleteRecursively()
    }

    @Test
    fun `clearing the wallpaper clears both the poster and motion keys`() = runTest(dispatcher) {
        // Stand in for a previously-applied motion wallpaper pair.
        context.pfpDataStore.edit {
            it[KEY_CUSTOM_WALLPAPER] = "/old/wallpaper.jpg"
            it[KEY_MOTION_WALLPAPER] = "/old/wallpaper.mp4"
        }

        vm.clearWallpaper()

        eventuallyPrefs("reset clears both keys") {
            it[KEY_CUSTOM_WALLPAPER] == null && it[KEY_MOTION_WALLPAPER] == null
        }
    }

    @Test
    fun `clearing the wallpaper deletes the cleared pair's files`() = runTest(dispatcher) {
        val dir = File(context.filesDir, "wallpaper").apply { mkdirs() }
        val pair = File(dir, "wallpaper_123.jpg").apply { writeText("x") }
        val motion = File(dir, "wallpaper_123.mp4").apply { writeText("x") }
        context.pfpDataStore.edit {
            it[KEY_CUSTOM_WALLPAPER] = pair.absolutePath
            it[KEY_MOTION_WALLPAPER] = motion.absolutePath
        }

        vm.clearWallpaper()

        eventually("both files of the cleared pair are removed") {
            !pair.isFile && !motion.isFile
        }
    }

    @Test
    fun `still image import writes the poster pref and keeps working as before`() = runTest(dispatcher) {
        val uri = registerStream(jpegBytes())

        vm.onWallpaperPicked(uri)

        val prefs = eventuallyPrefs("still import sets the poster pref") {
            it[KEY_CUSTOM_WALLPAPER] != null
        }
        val path = prefs[KEY_CUSTOM_WALLPAPER]!!
        assertTrue(File(path).isFile)
        assertTrue("a still import must not set the motion key", prefs[KEY_MOTION_WALLPAPER] == null)
    }

    @Test
    fun `a still import replaces a motion wallpaper wholesale`() = runTest(dispatcher) {
        // Previously-applied motion pair, files on disk.
        val dir = File(context.filesDir, "wallpaper").apply { mkdirs() }
        val oldPoster = File(dir, "wallpaper_1.jpg").apply { writeText("old") }
        val oldMotion = File(dir, "wallpaper_1.mp4").apply { writeText("old") }
        context.pfpDataStore.edit {
            it[KEY_CUSTOM_WALLPAPER] = oldPoster.absolutePath
            it[KEY_MOTION_WALLPAPER] = oldMotion.absolutePath
        }

        val uri = registerStream(jpegBytes())
        vm.onWallpaperPicked(uri)

        val prefs = eventuallyPrefs("the still import applies") {
            it[KEY_CUSTOM_WALLPAPER] != null && it[KEY_CUSTOM_WALLPAPER] != oldPoster.absolutePath
        }
        assertTrue(
            "the motion key must clear when a still replaces the pair",
            prefs[KEY_MOTION_WALLPAPER] == null,
        )
        eventually("the old pair's files are pruned") {
            !oldPoster.isFile && !oldMotion.isFile
        }
    }

    /*
     * Not unit-testable on the JVM (needs a device pass, like PfpThemeStoreTest's decode-failure
     * note): the undecodable-still rejection — Robolectric's BitmapFactory shadow returns a
     * placeholder bitmap for arbitrary bytes instead of failing, so the import always "succeeds".
     */

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * Waits until [condition] holds. Drives the test scheduler (the VM's coroutines) and, in
     * the same loop, sleeps on a REAL IO thread (never the scheduler thread) so wall-clock
     * work — DataStore writes, file deletes — gets time to land.
     */
    private suspend fun TestScope.eventually(reason: String, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("condition not met within 10s: $reason")
            }
            advanceUntilIdle()
            withContext(Dispatchers.IO) { Thread.sleep(25) }
        }
        advanceUntilIdle()
    }

    /** Polls fresh prefs snapshots until [predicate] holds, returning the settled snapshot. */
    private suspend fun TestScope.eventuallyPrefs(
        reason: String,
        predicate: (Preferences) -> Boolean,
    ): Preferences {
        var snapshot: Preferences? = null
        eventually(reason) {
            snapshot = context.pfpDataStore.data.first()
            predicate(snapshot!!)
        }
        return snapshot!!
    }

    private fun registerStream(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://test/${System.nanoTime()}/pick")
        shadowOf(context.contentResolver).registerInputStream(uri, bytes.inputStream())
        return uri
    }

    private fun jpegBytes(width: Int = 64, height: Int = 64): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        return java.io.ByteArrayOutputStream().also {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
    }

    private companion object {
        // Mirror the (internal) ViewModel keys by their string contract, like PfpThemeStoreTest does.
        val KEY_CUSTOM_WALLPAPER = stringPreferencesKey("display_custom_wallpaper")
        val KEY_MOTION_WALLPAPER = stringPreferencesKey("display_motion_wallpaper")
    }
}
