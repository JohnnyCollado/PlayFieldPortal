package com.playfieldportal.core.common.security

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
// Pin to minSdk so the sanitizer takes the legacy resolveActivity(Intent, Int) path (the API-33
// ResolveInfoFlags overload isn't present in Robolectric's runtime android image).
@Config(manifest = Config.NONE, sdk = [29])
class ShortcutIntentSanitizerTest {

    private val pm = mockk<PackageManager>()

    private val grantFlags =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    private fun stubResolves(resolves: Boolean) {
        @Suppress("DEPRECATION")
        every { pm.resolveActivity(any(), any<Int>()) } returns (if (resolves) ResolveInfo() else null)
    }

    @Test
    fun `strips every URI-permission grant flag`() {
        stubResolves(true)
        val raw = Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName("com.evil", "com.evil.Activity")
            data = Uri.parse("content://com.playfieldportal.launcher.fileprovider/storage_volumes/emulated/0/secret")
            addFlags(grantFlags)
        }
        val safe = ShortcutIntentSanitizer.sanitize(raw, pm)!!
        assertEquals(0, safe.flags and grantFlags, "grant flags must be cleared")
    }

    @Test
    fun `clears ClipData (can carry granted content URIs)`() {
        stubResolves(true)
        val raw = Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName("com.evil", "com.evil.Activity")
            clipData = android.content.ClipData.newRawUri("x", Uri.parse("content://secret"))
        }
        assertNull(ShortcutIntentSanitizer.sanitize(raw, pm)!!.clipData)
    }

    @Test
    fun `preserves the legitimate target component and extras`() {
        stubResolves(true)
        val target = ComponentName("com.bannerhub", "com.bannerhub.LaunchActivity")
        val raw = Intent(Intent.ACTION_VIEW).apply {
            component = target
            putExtra("game_id", "42")
        }
        val safe = ShortcutIntentSanitizer.sanitize(raw, pm)!!
        assertEquals(target, safe.component)
        assertEquals("42", safe.getStringExtra("game_id"))
    }

    @Test
    fun `rejects an intent that does not resolve to an installed app`() {
        stubResolves(false)
        val raw = Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName("com.missing", "com.missing.Activity")
        }
        assertNull(ShortcutIntentSanitizer.sanitize(raw, pm))
    }
}
