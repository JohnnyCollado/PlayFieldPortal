package com.playfieldportal.core.domain.model.appupdate

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** A plain `major.minor.patch` release version. Ordered by number, so 1.10.0 is newer than 1.9.9. */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int =
        compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val TAG = Regex("""v?(\d{1,6})\.(\d{1,6})\.(\d{1,6})""")
        private val VERSION_NAME = Regex("""(\d{1,6})\.(\d{1,6})\.(\d{1,6})(?:-.*)?""")

        /**
         * A release tag: exactly `X.Y.Z`, with an optional leading `v`. Anything else — the
         * `emulator-kb` asset release, an alpha tag — is not an app release and returns null.
         */
        fun parseTag(tag: String): SemVer? = TAG.matchEntire(tag.trim())?.toSemVer()

        /** The installed `versionName`, which carries flavor and build suffixes (`1.3.3-lite-debug`). */
        fun fromVersionName(versionName: String): SemVer? = VERSION_NAME.matchEntire(versionName.trim())?.toSemVer()

        private fun MatchResult.toSemVer(): SemVer? {
            val (major, minor, patch) = destructured
            return SemVer(major.toIntOrNull() ?: return null, minor.toIntOrNull() ?: return null, patch.toIntOrNull() ?: return null)
        }
    }
}

/** Which of the two published APKs this install is. */
enum class AppEdition(val assetSuffix: String, val label: String) {
    FULL("full", "Full"),
    LITE("lite", "Lite");

    companion object {
        /** The lite build's application id carries a `.lite` segment (app/build.gradle.kts). */
        fun fromPackageName(packageName: String): AppEdition =
            if (packageName.split('.').contains("lite")) LITE else FULL
    }
}

@Serializable
data class AppReleaseApk(val name: String, val url: String, val sizeBytes: Long = 0)

/**
 * One published app release, reduced to what the System Update screen shows. [summary] is the short
 * list written for the changelog; the detailed notes stay on GitHub behind [notesUrl].
 */
@Serializable
data class AppRelease(
    val version: String,
    /** `YYYY-MM-DD`, or blank when the server sent no date. */
    val publishedOn: String = "",
    val summary: List<String> = emptyList(),
    val notesUrl: String,
    val apks: List<AppReleaseApk> = emptyList(),
) {
    val semVer: SemVer? get() = SemVer.parseTag(version)

    /** The APK for [edition], named the way the release build names it. Null when not attached. */
    fun apkFor(edition: AppEdition): AppReleaseApk? =
        apks.firstOrNull { it.name == "PlayFieldPortal-$version-${edition.assetSuffix}.apk" }
}

/**
 * Reads GitHub's "list releases" response for this repository.
 *
 * Everything in it is treated as untrusted text: only `X.Y.Z` tags count, links and downloads must
 * sit under this repository's releases, and the changelog lines are capped. The list is used rather
 * than "latest release" because the repo also publishes non-app releases (the emulator knowledge
 * file), any of which could become "latest".
 */
object AppReleaseParser {

    const val RELEASES_API_URL = "https://api.github.com/repos/JohnnyCollado/PlayFieldPortal/releases?per_page=30"
    const val RELEASES_PAGE_URL = "https://github.com/JohnnyCollado/PlayFieldPortal/releases"
    private const val DOWNLOAD_PREFIX = "$RELEASES_PAGE_URL/download/"

    const val MAX_SUMMARY_LINES = 8
    const val MAX_SUMMARY_LINE_LENGTH = 160

    private val json = Json { ignoreUnknownKeys = true }
    private val summaryHeading = Regex("""#{1,6}\s*summary\s*""", RegexOption.IGNORE_CASE)
    private val markdownLink = Regex("""\[([^\]]*)]\([^)]*\)""")

    /** Newest first. Null when [text] is not a release list at all (an error body, a rate-limit page). */
    fun parse(text: String): List<AppRelease>? {
        val array = try {
            json.parseToJsonElement(text) as? JsonArray
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        return array.mapNotNull { (it as? JsonObject)?.let(::toRelease) }
            .sortedByDescending { it.semVer }
    }

    private fun toRelease(obj: JsonObject): AppRelease? {
        if (obj.boolean("draft") || obj.boolean("prerelease")) return null
        val tag = obj.string("tag_name") ?: return null
        val version = SemVer.parseTag(tag) ?: return null
        val htmlUrl = obj.string("html_url")
        return AppRelease(
            version = version.toString(),
            publishedOn = obj.string("published_at")?.take(10).orEmpty(),
            summary = summaryOf(obj.string("body").orEmpty()),
            notesUrl = htmlUrl?.takeIf { it.startsWith("$RELEASES_PAGE_URL/") } ?: "$RELEASES_PAGE_URL/tag/$tag",
            apks = (obj["assets"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::toApk) },
        )
    }

    private fun toApk(obj: JsonObject): AppReleaseApk? {
        val name = obj.string("name")?.takeIf { it.endsWith(".apk") } ?: return null
        val url = obj.string("browser_download_url")?.takeIf { it.startsWith(DOWNLOAD_PREFIX) } ?: return null
        return AppReleaseApk(name, url, (obj["size"] as? JsonPrimitive)?.longOrNull ?: 0)
    }

    /**
     * The bullets under a `## Summary` heading in a release's notes, as plain text. A release
     * without that block has no changelog lines: its detailed notes are not summarised by guessing.
     */
    fun summaryOf(body: String): List<String> {
        val lines = body.lines()
        val start = lines.indexOfFirst { summaryHeading.matches(it.trim()) }
        if (start < 0) return emptyList()
        val bullets = mutableListOf<String>()
        for (raw in lines.drop(start + 1)) {
            val line = raw.trim()
            when {
                line.startsWith("#") -> break
                line.startsWith("- ") || line.startsWith("* ") -> bullets += line.drop(2)
                // A hard-wrapped bullet continues on an indented line.
                line.isNotEmpty() && bullets.isNotEmpty() -> bullets[bullets.lastIndex] = bullets.last() + " " + line
            }
        }
        return bullets.map(::plainText).filter { it.isNotEmpty() }.take(MAX_SUMMARY_LINES)
    }

    private fun plainText(markdown: String): String =
        markdownLink.replace(markdown, "$1")
            .replace("**", "").replace("`", "")
            .trim()
            .take(MAX_SUMMARY_LINE_LENGTH)

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.boolean(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull == true
}
