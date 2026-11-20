package com.playfieldportal.core.domain.model.appupdate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppReleaseParserTest {

    private val base = "https://github.com/JohnnyCollado/PlayFieldPortal/releases"

    private fun release(
        tag: String,
        body: String = "",
        draft: Boolean = false,
        prerelease: Boolean = false,
        htmlUrl: String = "$base/tag/$tag",
        assets: String = """
            {"name":"PlayFieldPortal-$tag-full.apk","browser_download_url":"$base/download/$tag/PlayFieldPortal-$tag-full.apk","size":1000},
            {"name":"PlayFieldPortal-$tag-lite.apk","browser_download_url":"$base/download/$tag/PlayFieldPortal-$tag-lite.apk","size":900}
        """,
    ): String {
        val escaped = body.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"published_at":"2026-10-23T12:00:00Z",
            "html_url":"$htmlUrl","body":"$escaped","assets":[$assets]}"""
    }

    private fun parse(vararg releases: String) = AppReleaseParser.parse("[${releases.joinToString(",")}]")

    // ── SemVer ────────────────────────────────────────────────────────────────

    @Test
    fun `versions compare by number, not as text`() {
        assertTrue(SemVer(1, 10, 0) > SemVer(1, 9, 9))
        assertTrue(SemVer(2, 0, 0) > SemVer(1, 99, 99))
        assertEquals(SemVer(1, 3, 3), SemVer.parseTag("1.3.3"))
        assertEquals(SemVer(1, 3, 3), SemVer.parseTag("v1.3.3"))
    }

    @Test
    fun `a tag that is not a plain release version is not a version`() {
        assertNull(SemVer.parseTag("emulator-kb"))
        assertNull(SemVer.parseTag("v1.0.0-alpha.1"))
        assertNull(SemVer.parseTag("1.3"))
        assertNull(SemVer.parseTag(""))
    }

    @Test
    fun `the installed version name ignores its flavor and build suffixes`() {
        assertEquals(SemVer(1, 3, 3), SemVer.fromVersionName("1.3.3"))
        assertEquals(SemVer(1, 3, 3), SemVer.fromVersionName("1.3.3-lite"))
        assertEquals(SemVer(1, 3, 3), SemVer.fromVersionName("1.3.3-lite-debug"))
        assertNull(SemVer.fromVersionName("unknown"))
    }

    // ── Release list ──────────────────────────────────────────────────────────

    @Test
    fun `releases come back newest first whatever order the server used`() {
        val releases = assertNotNull(parse(release("1.3.2"), release("1.10.0"), release("1.3.3")))

        assertEquals(listOf("1.10.0", "1.3.3", "1.3.2"), releases.map { it.version })
        assertEquals("2026-10-23", releases.first().publishedOn)
    }

    @Test
    fun `drafts, pre-releases and non-app releases are left out`() {
        val releases = assertNotNull(
            parse(
                release("1.3.3"),
                release("1.4.0", draft = true),
                release("1.5.0", prerelease = true),
                release("emulator-kb"),
                release("v1.0.0-alpha.1"),
            ),
        )

        assertEquals(listOf("1.3.3"), releases.map { it.version })
    }

    @Test
    fun `a response that is not a release list is refused rather than read as no releases`() {
        assertNull(AppReleaseParser.parse("""{"message":"API rate limit exceeded"}"""))
        assertNull(AppReleaseParser.parse("<html>"))
        assertEquals(emptyList(), AppReleaseParser.parse("[]"))
    }

    // ── Assets and links ──────────────────────────────────────────────────────

    @Test
    fun `each edition gets its own apk`() {
        val release = assertNotNull(parse(release("1.3.4"))).single()

        assertEquals("PlayFieldPortal-1.3.4-full.apk", release.apkFor(AppEdition.FULL)?.name)
        assertEquals("PlayFieldPortal-1.3.4-lite.apk", release.apkFor(AppEdition.LITE)?.name)
        assertEquals(900L, release.apkFor(AppEdition.LITE)?.sizeBytes)
    }

    @Test
    fun `an apk hosted anywhere but this repo's releases is ignored`() {
        val release = assertNotNull(
            parse(
                release(
                    "1.3.4",
                    assets = """{"name":"PlayFieldPortal-1.3.4-full.apk","browser_download_url":"https://example.com/PlayFieldPortal-1.3.4-full.apk","size":1}""",
                ),
            ),
        ).single()

        assertNull(release.apkFor(AppEdition.FULL))
    }

    @Test
    fun `the release notes link always points at this repo`() {
        val release = assertNotNull(parse(release("1.3.4", htmlUrl = "https://example.com/phish"))).single()

        assertEquals("$base/tag/1.3.4", release.notesUrl)
    }

    @Test
    fun `the edition follows the installed package id`() {
        assertEquals(AppEdition.FULL, AppEdition.fromPackageName("com.playfieldportal.launcher"))
        assertEquals(AppEdition.LITE, AppEdition.fromPackageName("com.playfieldportal.launcher.lite"))
        assertEquals(AppEdition.LITE, AppEdition.fromPackageName("com.playfieldportal.launcher.lite.debug"))
    }

    // ── Summary block ─────────────────────────────────────────────────────────

    @Test
    fun `the summary block becomes the changelog lines`() {
        val body = """
            ## Summary

            - Improved RetroArch launching and settings
            - **Added** in-app `system updates`
            - Improved system performance, see [the notes](https://example.com)

            ## New Features

            - **Your own icon on a custom memory card.** Lots of detail here.
        """.trimIndent()

        val release = assertNotNull(parse(release("1.3.4", body = body))).single()

        assertEquals(
            listOf(
                "Improved RetroArch launching and settings",
                "Added in-app system updates",
                "Improved system performance, see the notes",
            ),
            release.summary,
        )
    }

    @Test
    fun `a wrapped summary line is joined back together`() {
        val body = "## Summary\n- Improved system performance across\n  the whole launcher\n- Second line"

        assertEquals(
            listOf("Improved system performance across the whole launcher", "Second line"),
            AppReleaseParser.summaryOf(body),
        )
    }

    @Test
    fun `a release with no summary block has no changelog lines`() {
        // The detailed notes make specific claims; the changelog shows only what was written for it.
        assertEquals(emptyList(), AppReleaseParser.summaryOf("## New Features\n\n- **Something specific.** Detail."))
        assertEquals(emptyList(), AppReleaseParser.summaryOf(""))
    }

    @Test
    fun `an oversized summary is capped`() {
        val body = "## Summary\n" + (1..40).joinToString("\n") { "- Line $it " + "x".repeat(400) }

        val summary = AppReleaseParser.summaryOf(body)

        assertEquals(AppReleaseParser.MAX_SUMMARY_LINES, summary.size)
        assertTrue(summary.all { it.length <= AppReleaseParser.MAX_SUMMARY_LINE_LENGTH })
    }
}
