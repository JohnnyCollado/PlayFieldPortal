package com.playfieldportal.feature.launcher

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Winlator-family `.desktop` exports. Every fork (CMOD, Ludashi, Bannerlator, …) writes them into
 * the same shared `Winlator/Shortcuts` folder, so the folder says nothing about which app made a
 * file — the file's own private paths do (`/data/user/0/<package>/…`). Any installed fork can open
 * a shared file; a file inside one app's private storage only that app can.
 */
object WinlatorDesktop {

    /**
     * Bannerlator's (star-lineage) game screen: exported, and the only door that takes a
     * `shortcut_path` — its main screen ignores the extra. A missing `container_id` is parsed from
     * the file, so none is sent.
     */
    const val STAR_DISPLAY_ACTIVITY = "com.winlator.star.XServerDisplayActivity"

    private const val SHORTCUT_PATH_EXTRA = "shortcut_path"
    private const val MAX_APP_ID_LENGTH = 12
    private val STEAM_APP_ID = Regex("""(?m)^\s*steamAppId=(\d+)\s*$""")

    // /data/user/<n>/<package>/… and the legacy /data/data/<package>/… form.
    private val PRIVATE_PATH = Regex("""/data/(?:user/\d+|data)/([A-Za-z][\w.]*)/""")

    /** The launcher in [candidates] whose private storage [desktopText] points into, or null. */
    fun ownerPackage(desktopText: String, candidates: Collection<String>): String? =
        PRIVATE_PATH.findAll(desktopText).map { it.groupValues[1] }.firstOrNull { it in candidates }

    /**
     * The Steam appid the fork recorded for the game (`steamAppId=` under `[Extra Data]`), or null
     * when there is none or it is not a plausible appid (digits, 1–12 long, not 0).
     */
    fun steamAppId(desktopText: String): String? =
        STEAM_APP_ID.find(desktopText)?.groupValues?.get(1)
            ?.takeIf { it.length <= MAX_APP_ID_LENGTH && it.trimStart('0').isNotEmpty() }

    /** True when [shortcutPath] is on shared storage, where any fork can read it. */
    fun isShared(shortcutPath: String): Boolean =
        !shortcutPath.startsWith("/data/") && !APP_SPECIFIC_EXTERNAL.containsMatchIn(shortcutPath)

    // An app's own external folders are private to it on Android 11+, so another fork cannot read
    // a shortcut stored there (survey L12, docs/audits/codebase-survey-2026-10-22.md).
    private val APP_SPECIFIC_EXTERNAL = Regex("/Android/(data|obb)/", RegexOption.IGNORE_CASE)

    /** The `.desktop` path a stored launch intent opens, or null when it is not a desktop launch. */
    fun shortcutPathOf(intentUri: String?): String? = intentUri
        ?.let { runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }.getOrNull() }
        ?.getStringExtra(SHORTCUT_PATH_EXTRA)
        ?.takeIf { it.isNotBlank() }

    /** The intent that opens [shortcutPath] in the installed fork [packageName], or null. */
    fun launchIntent(packageName: String, shortcutPath: String, pm: PackageManager): Intent? =
        launchIntent(
            packageName = packageName,
            shortcutPath = shortcutPath,
            hasStarDisplay = runCatching {
                pm.getActivityInfo(ComponentName(packageName, STAR_DISPLAY_ACTIVITY), 0).exported
            }.getOrDefault(false),
            packageLaunch = pm.getLaunchIntentForPackage(packageName),
        )

    internal fun launchIntent(
        packageName: String,
        shortcutPath: String,
        hasStarDisplay: Boolean,
        packageLaunch: Intent?,
    ): Intent? {
        val base = if (hasStarDisplay) {
            Intent(Intent.ACTION_VIEW).setComponent(ComponentName(packageName, STAR_DISPLAY_ACTIVITY))
        } else {
            packageLaunch ?: return null
        }
        return base.apply {
            putExtra(SHORTCUT_PATH_EXTRA, shortcutPath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }
}
