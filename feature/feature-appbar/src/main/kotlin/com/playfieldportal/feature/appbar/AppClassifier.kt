package com.playfieldportal.feature.appbar

import android.content.pm.ApplicationInfo
import javax.inject.Inject
import javax.inject.Singleton

// Category ids these match the seeded built-in categories in CategoryRepositoryImpl.
object AppCategoryIds {
    const val PHOTO     = "photos"
    const val MUSIC     = "music"
    const val VIDEO     = "videos"
    const val NETWORK   = "network"
    const val APP_STORE = "app_store"
}

// Produces the DEFAULT category placement for an installed app. This is only a starting
// point — once the user customizes an app's placement, AppCategoryRepository ignores this.
//
// Resolution order: curated package database → Android system category → label keywords.
@Singleton
class AppClassifier @Inject constructor() {

    fun defaultCategories(app: InstalledApp): Set<String> {
        // 1) Curated database — exact or prefix match wins.
        curatedCategory(app.packageName)?.let { return setOf(it) }

        // 2) Android-declared application category.
        systemCategory(app.systemCategory)?.let { return setOf(it) }

        // 3) Label keyword fallback (e.g. generic "Browser").
        labelCategory(app.label)?.let { return setOf(it) }

        return emptySet()   // unclassified — remains reachable via the App Drawer
    }

    private fun curatedCategory(pkg: String): String? {
        val p = pkg.lowercase()
        CURATED.forEach { (categoryId, prefixes) ->
            if (prefixes.any { p == it || p.startsWith("$it.") || p.startsWith(it) }) return categoryId
        }
        return null
    }

    private fun systemCategory(category: Int): String? = when (category) {
        ApplicationInfo.CATEGORY_VIDEO  -> AppCategoryIds.VIDEO
        ApplicationInfo.CATEGORY_AUDIO  -> AppCategoryIds.MUSIC
        ApplicationInfo.CATEGORY_IMAGE  -> AppCategoryIds.PHOTO
        else                            -> null
    }

    private fun labelCategory(label: String): String? {
        val l = label.lowercase()
        return when {
            l.contains("browser")                      -> AppCategoryIds.NETWORK
            l.contains("app store") || l.contains("appstore") ||
                l.contains("app manager")              -> AppCategoryIds.APP_STORE
            else                                       -> null
        }
    }

    private companion object {
        // categoryId → known package-name prefixes
        val CURATED: Map<String, List<String>> = mapOf(
            AppCategoryIds.VIDEO to listOf(
                "com.google.android.youtube", "com.google.android.apps.youtube",
                "com.netflix.mediaclient", "com.hulu.plus", "com.disney.disneyplus",
                "com.amazon.avod", "com.plexapp.android", "org.videolan.vlc",
                "org.xbmc.kodi", "com.mxtech.videoplayer", "com.crunchyroll.crunchyroid",
                "tv.twitch.android.app", "com.hbo.hbonow", "com.wbd.stream",
                "com.google.android.videos", "com.spotify.tv.android",
            ),
            AppCategoryIds.NETWORK to listOf(
                "com.android.chrome", "com.google.android.apps.chrome",
                "org.mozilla.firefox", "org.mozilla.fenix", "com.brave.browser",
                "com.microsoft.emmx", "com.opera.browser", "com.opera.mini.native",
                "com.duckduckgo.mobile.android", "com.sec.android.app.sbrowser",
                "com.UCMobile.intl", "com.kiwibrowser.browser", "org.torproject.torbrowser",
                "mark.via", "com.android.browser",
            ),
            AppCategoryIds.APP_STORE to listOf(
                "com.android.vending",                 // Google Play Store
                "com.aurora.store",                    // Aurora Store
                "com.apkpure.aegon",                   // APKPure
                "org.fdroid.fdroid",                   // F-Droid
                "com.machiav3lli.fdroid",              // Neo Store
                "com.looker.droidify",                 // Droid-ify
                "crocodile.obtainium",                 // Obtainium
                "com.amazon.venezia",                  // Amazon Appstore
                "cm.aptoide.pt",                       // Aptoide
                "com.sec.android.app.samsungapps",     // Galaxy Store
                "com.huawei.appmarket",                // AppGallery
                "com.xiaomi.market",                   // GetApps
                "com.qooapp.qoohelper",                // QooApp
            ),
            AppCategoryIds.MUSIC to listOf(
                "com.spotify.music", "com.google.android.apps.youtube.music",
                "com.amazon.mp3", "com.apple.android.music", "deezer.android.app",
                "com.soundcloud.android", "com.pandora.android", "tunein.player",
                "com.maxmpz.audioplayer", "org.videolan.vlc.music",
            ),
            AppCategoryIds.PHOTO to listOf(
                "com.google.android.apps.photos", "com.sec.android.gallery3d",
                "com.instagram.android", "com.pinterest", "com.adobe.lrmobile",
                "com.google.android.GoogleCamera", "net.sourceforge.opencamera",
            ),
        )
    }
}
