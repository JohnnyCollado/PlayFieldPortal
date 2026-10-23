package com.playfieldportal.core.data.saf

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A folder listing that fails marks the walk incomplete instead of reading as "no files".
 *
 * Survey L17 (docs/audits/codebase-survey-2026-10-22.md): the walk used the empty-on-failure SAF
 * query, so a provider that could not list a folder looked like an empty folder and the music,
 * photo and video scanners replaced their library with nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SafTreeWalkTest {

    @Test
    fun `an unreadable root makes the walk incomplete`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // No provider answers for this authority under Robolectric, so the listing comes back null.
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AMusic")

        val walk = context.walkSafTree(tree, recursive = true)

        assertTrue(walk.incomplete)
        assertTrue(walk.files.isEmpty())
    }
}
