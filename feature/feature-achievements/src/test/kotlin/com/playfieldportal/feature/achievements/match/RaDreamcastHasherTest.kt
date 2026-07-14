package com.playfieldportal.feature.achievements.match

import com.playfieldportal.core.domain.model.Game
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests Dreamcast GD-ROM hashing: IP.BIN (256 bytes) + boot-executable contents, addressed across a
 * GDI's tracks with the data track starting deep in the disc (LBA 45000, exercising the non-zero
 * firstTrackSector base). Covers both the in-memory track model and the `.gdi` parse.
 */
class RaDreamcastHasherTest {

    private val temp = mutableListOf<File>()

    @AfterTest
    fun cleanup() = temp.forEach { it.deleteRecursively() }

    private class MemSource(private val data: ByteArray) : DiscImage.SeekableSource {
        override fun readFully(offset: Long, dest: ByteArray, len: Int): Int {
            if (offset >= data.size) return 0
            val n = minOf(len.toLong(), data.size - offset).toInt()
            System.arraycopy(data, offset.toInt(), dest, 0, n)
            return n
        }
        override fun close() {}
    }

    private fun md5(vararg parts: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        parts.forEach { md.update(it) }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private val bootBytes = ByteArray(500) { (it * 7 and 0xFF).toByte() }

    // A track-3 image: a cooked ISO whose records use absolute LBAs (base 45000), with IP.BIN written
    // over the first sector. Returns the image bytes and the 256-byte IP.BIN used.
    private fun track3Image(bootName: String, base: Int): Pair<ByteArray, ByteArray> {
        val iso = SyntheticIso().addFile(bootName, bootBytes).build(baseLba = base)
        val ip = ByteArray(256)
        "SEGA SEGAKATANA ".toByteArray(Charsets.US_ASCII).copyInto(ip, 0)
        for (i in 96 until 112) ip[i] = ' '.code.toByte()             // boot-name field is space-padded
        bootName.toByteArray(Charsets.US_ASCII).copyInto(ip, 96)
        ip.copyInto(iso, 0)                                           // IP.BIN at the start of the track
        return iso to ip
    }

    @Test
    fun `hashes ip-bin plus the boot executable across a gdi data track at lba 45000`() {
        val base = 45000
        val (iso, ip) = track3Image("1ST_READ.BIN", base)

        val track3 = GdiTrackSource.Track(
            number = 3, type = 4, startLba = base, sectorCount = iso.size / 2048,
            sectorSize = 2048, dataOffset = 0, fileOffset = 0, source = MemSource(iso),
        )
        val source = GdiTrackSource(listOf(track3))
        val ipStart = source.ipBinTrackStart()
        assertEquals(base, ipStart)

        val hash = DiscImage.openTracks(source, ipStart!!).use { RaDreamcastHasher.hash(it) }
        assertEquals(md5(ip, bootBytes), hash)
    }

    @Test
    fun `a disc without the SEGAKATANA header is not identified`() {
        val iso = SyntheticIso().addFile("1ST_READ.BIN", bootBytes).build(baseLba = 45000) // no IP.BIN
        val track3 = GdiTrackSource.Track(
            number = 3, type = 4, startLba = 45000, sectorCount = iso.size / 2048,
            sectorSize = 2048, dataOffset = 0, fileOffset = 0, source = MemSource(iso),
        )
        assertNull(GdiTrackSource(listOf(track3)).ipBinTrackStart())
    }

    @Test
    fun `opens and hashes a gdi from its index file`() = runTest {
        val base = 45000
        val (iso, ip) = track3Image("1ST_READ.BIN", base)

        val dir = Files.createTempDirectory("gdi").toFile().also { temp += it }
        File(dir, "track01.bin").writeBytes(ByteArray(2048))          // dummy low-density track
        File(dir, "track03.bin").writeBytes(iso)
        val gdiFile = File(dir, "game.gdi")
        gdiFile.writeText(
            """
            2
            1 0 4 2048 track01.bin 0
            3 $base 4 2048 track03.bin 0
            """.trimIndent(),
        )

        val opener = DiscImageOpener(mockk(relaxed = true))
        val game = Game(id = 1, title = "Test", platformId = "dreamcast", romPath = gdiFile.absolutePath)

        val image = opener.openGdi(game)!!
        val hash = image.use { RaDreamcastHasher.hash(it) }
        assertEquals(md5(ip, bootBytes), hash)
    }
}
