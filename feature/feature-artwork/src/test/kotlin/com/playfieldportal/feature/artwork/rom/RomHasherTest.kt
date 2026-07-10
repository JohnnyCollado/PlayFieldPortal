package com.playfieldportal.feature.artwork.rom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test

class RomHasherTest {

    private val payload = "hello world".toByteArray(Charsets.US_ASCII)
    private val payloadCrc = CRC32().apply { update(payload) }
        .value.toString(16).uppercase().padStart(8, '0')

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

    @Test
    fun `crcOfStream hashes and counts bytes`() {
        val (crc, size) = RomHasher.crcOfStream(ByteArrayInputStream(payload), maxHashBytes = 1024)
        assertEquals(payloadCrc, crc)
        assertEquals(payload.size.toLong(), size)
    }

    @Test
    fun `crcOfStream gives up past the size cap`() {
        val (crc, size) = RomHasher.crcOfStream(ByteArrayInputStream(payload), maxHashBytes = 4)
        assertNull(crc)
        assertNull(size)
    }

    @Test
    fun `zip hashing targets the inner rom, not the archive`() {
        val zip = zipOf("game.gba" to payload)
        val result = RomHasher.hashFirstZipEntry(ByteArrayInputStream(zip), maxHashBytes = 1024)
        assertEquals(payloadCrc, result?.crc32)
        assertEquals(payload.size.toLong(), result?.sizeBytes)
        assertEquals("game.gba", result?.fileName)
    }

    @Test
    fun `zip hashing skips packaging noise entries`() {
        val zip = zipOf(
            "readme.txt" to "ignore me".toByteArray(),
            "info.nfo"   to "ignore me too".toByteArray(),
            "game.sfc"   to payload,
        )
        val result = RomHasher.hashFirstZipEntry(ByteArrayInputStream(zip), maxHashBytes = 1024)
        assertEquals("game.sfc", result?.fileName)
        assertEquals(payloadCrc, result?.crc32)
    }

    @Test
    fun `zip with no usable entry returns null`() {
        val zip = zipOf("notes.txt" to "just text".toByteArray())
        assertNull(RomHasher.hashFirstZipEntry(ByteArrayInputStream(zip), maxHashBytes = 1024))
    }
}
