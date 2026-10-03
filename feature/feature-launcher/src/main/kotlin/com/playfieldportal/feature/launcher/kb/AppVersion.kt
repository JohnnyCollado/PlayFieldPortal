package com.playfieldportal.feature.launcher.kb

import android.content.Context

/** This app's version code, or 0 when it cannot be read. */
fun Context.appVersionCode(): Int = runCatching {
    // longVersionCode is available from API 28; minSdk is 29.
    packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
}.getOrDefault(0)
