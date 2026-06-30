package com.playfieldportal.core.common.security

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * Helpers for the All-Files-Access (`MANAGE_EXTERNAL_STORAGE`) special permission.
 *
 * PFP needs broad file access only to scan file-based ROM folders (incl. multi-file/disc games
 * that emulators read by real path). To keep the privacy footprint minimal, the app requests it
 * point-of-need — only when the user adds a file-based ROM folder — instead of up front.
 *
 * On Android ≤ 10 (API 29) the legacy storage model applies, so no special access is needed.
 */
object StorageAccess {

    /** True when the app can read arbitrary files (or doesn't need the special permission). */
    fun isManagerGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /** Intent that takes the user to the All-Files-Access settings screen for this app. */
    fun manageAccessIntent(context: Context): Intent {
        val appSpecific = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        )
        return if (appSpecific.resolveActivity(context.packageManager) != null) appSpecific
        else Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }
}
