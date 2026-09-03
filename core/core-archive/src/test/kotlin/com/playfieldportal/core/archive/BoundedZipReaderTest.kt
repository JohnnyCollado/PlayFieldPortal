package com.playfieldportal.core.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The caps here are the union of what the three call sites used to enforce separately: the theme
 * loader had an entry count and a total, the theme codec had a per-entry size and no count, and
 * the backup reader had nothing at all.
 */
class BoundedZipReaderTest {

    @get:Rule val temp = TemporaryFolder()

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun readAll(bytes: ByteArray, limits: ZipLimits): Map<String, ByteArray> {
        val seen = linkedMapOf<String, ByteArray>()
        BoundedZipReader.read(ByteArrayInputStream(bytes), limits) { entry ->
            if (!entry.isDirectory) seen[entry.name] = entry.readBytes()
        }
        return seen
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    fun `reads every entry of a well-formed archive`() {
        val bytes = zipOf("a.json" to "one".toByteArray(), "nested/b.json" to "two".toByteArray())

        val seen = readAll(bytes, ZipLimits())

        assertEquals(setOf("a.json", "nested/b.json"), seen.keys)
        assertEquals("one", String(seen.getValue("a.json")))
    }

    // ── Entry-count bomb ──────────────────────────────────────────────────────
    // PfpThemeCodec bounded memory per entry but never counted entries, so a small bundle of
    // repeated wallpaper.png entries was an unbounded hang re-triggered on every store scan.

    @Test
    fun `refuses an archive with more entries than the cap`() {
        val many = Array(20) { "e$it.txt" to "x".toByteArray() }
        val bytes = zipOf(*many)

        val rejection = rejectionFrom { readAll(bytes, ZipLimits(maxEntries = 10)) }

        assertTrue(rejection is ZipRejection.TooManyEntries)
        assertEquals(10, (rejection as ZipRejection.TooManyEntries).limit)
    }

    // ── Compression bomb ──────────────────────────────────────────────────────
    // BackupManager.readBackup inflated every non-file entry with an uncapped readBytes() and
    // held it for the whole restore: a games.json of one repeated byte is a guaranteed OOM.

    @Test
    fun `refuses a single entry larger than the per-entry cap without inflating it`() {
        // 8 MB of one repeated byte deflates to a few KB — the archive is tiny, the payload is not.
        val bomb = ByteArray(8 * 1024 * 1024)
        val bytes = zipOf("games.json" to bomb)

        val rejection = rejectionFrom { readAll(bytes, ZipLimits(maxEntryBytes = 64 * 1024)) }

        assertTrue(rejection is ZipRejection.EntryTooLarge)
        assertEquals("games.json", (rejection as ZipRejection.EntryTooLarge).name)
    }

    @Test
    fun `refuses when the entries together exceed the archive cap`() {
        val chunk = ByteArray(64 * 1024)
        val bytes = zipOf("a" to chunk, "b" to chunk, "c" to chunk, "d" to chunk)

        val rejection = rejectionFrom { readAll(bytes, ZipLimits(maxTotalBytes = 100 * 1024)) }

        assertTrue(rejection is ZipRejection.ArchiveTooLarge)
    }

    @Test
    fun `copyTo is capped the same way as readBytes`() {
        val bomb = ByteArray(4 * 1024 * 1024)
        val bytes = zipOf("big.bin" to bomb)

        val rejection = rejectionFrom {
            BoundedZipReader.read(ByteArrayInputStream(bytes), ZipLimits(maxEntryBytes = 32 * 1024)) { entry ->
                entry.copyTo(ByteArrayOutputStream())
            }
        }

        assertTrue(rejection is ZipRejection.EntryTooLarge)
    }

    // ── Zip slip ──────────────────────────────────────────────────────────────

    @Test
    fun `resolveWithin refuses a parent traversal`() {
        val root = temp.newFolder("root")

        assertNull(SafeArchivePath.resolveWithin(root, "../escaped.txt"))
        assertNull(SafeArchivePath.resolveWithin(root, "nested/../../escaped.txt"))
    }

    @Test
    fun `resolveWithin refuses an absolute path`() {
        val root = temp.newFolder("root")

        assertNull(SafeArchivePath.resolveWithin(root, "/etc/passwd"))
        assertNull(SafeArchivePath.resolveWithin(root, File(temp.root, "sibling.txt").absolutePath))
    }

    @Test
    fun `resolveWithin accepts a nested relative path`() {
        val root = temp.newFolder("root")

        val resolved = SafeArchivePath.resolveWithin(root, "artwork/covers/1.png")

        assertTrue(resolved!!.canonicalPath.startsWith(root.canonicalPath + File.separator))
        assertEquals("1.png", resolved.name)
    }

    @Test
    fun `resolveWithin refuses an empty or dot-only name`() {
        val root = temp.newFolder("root")

        assertNull(SafeArchivePath.resolveWithin(root, ""))
        assertNull(SafeArchivePath.resolveWithin(root, "."))
        assertNull(SafeArchivePath.resolveWithin(root, "   "))
    }

    // ── Root confinement ──────────────────────────────────────────────────────

    @Test
    fun `resolveWithinRoots accepts only paths under a declared root`() {
        val base = temp.newFolder("files")
        val roots = listOf("artwork", "wallpaper")

        assertTrue(SafeArchivePath.resolveWithinRoots(base, "artwork/a.png", roots) != null)
        assertTrue(SafeArchivePath.resolveWithinRoots(base, "wallpaper/w.jpg", roots) != null)
        // The gap this closes: datastore/*.preferences_pb is under filesDir but under no root.
        assertNull(SafeArchivePath.resolveWithinRoots(base, "datastore/prefs.preferences_pb", roots))
        assertNull(SafeArchivePath.resolveWithinRoots(base, "pfpthemes/x.pfptheme", roots))
        // A root name is a whole path segment, not a prefix.
        assertNull(SafeArchivePath.resolveWithinRoots(base, "artwork_evil/a.png", roots))
    }

    @Test
    fun `resolveWithinRoots still refuses traversal that lands back inside a root`() {
        val base = temp.newFolder("files")

        assertNull(SafeArchivePath.resolveWithinRoots(base, "artwork/../../escaped", listOf("artwork")))
    }

    private fun rejectionFrom(block: () -> Unit): ZipRejection {
        return try {
            block()
            error("expected the read to be rejected")
        } catch (e: ZipLimitExceededException) {
            e.rejection
        }
    }
}
