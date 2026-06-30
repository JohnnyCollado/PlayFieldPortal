package com.playfieldportal.core.common.security

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Hardens externally-supplied launch intents (captured `INSTALL_SHORTCUT` broadcasts) before PFP
 * stores or starts them.
 *
 * PFP is a privileged deputy: it holds broad storage access and owns a FileProvider that can grant
 * read access to files under /storage. A malicious app could broadcast a crafted shortcut whose
 * intent carries a `content://` URI for one of those files plus `FLAG_GRANT_READ_URI_PERMISSION`,
 * tricking PFP into granting the attacker read access when the user later taps the entry
 * (confused-deputy / intent-redirection → arbitrary file read).
 *
 * [sanitize] neutralizes that by stripping every URI-permission grant flag and any ClipData, and by
 * pinning the intent to a concrete installed component so it can't be implicitly redirected. The
 * action/extras a legitimate game shortcut needs are preserved.
 */
object ShortcutIntentSanitizer {

    private val GRANT_FLAGS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    /**
     * Returns a launch-safe copy of [raw], or null if it can't be made safe (doesn't resolve to an
     * installed activity). The result never carries URI-permission grants and always targets an
     * explicit, installed component.
     */
    fun sanitize(raw: Intent, pm: PackageManager): Intent? {
        val safe = Intent(raw).apply {
            // A captured shortcut never needs PFP to grant URI permissions — this is the file-read
            // vector, so remove every grant flag and any ClipData that could carry granted URIs.
            flags = flags and GRANT_FLAGS.inv()
            clipData = null
        }

        // Pin to a concrete, installed component so the launch target can't be redirected. If the
        // intent is implicit, resolve it now and lock it to that result; if it already names a
        // component, require that component to actually resolve.
        if (safe.component == null) {
            val resolved = safe.resolveActivity(pm) ?: return null
            safe.component = resolved
        } else if (!resolves(safe, pm)) {
            return null
        }
        return safe
    }

    private fun resolves(intent: Intent, pm: PackageManager): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0)) != null
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, 0) != null
        }
}
