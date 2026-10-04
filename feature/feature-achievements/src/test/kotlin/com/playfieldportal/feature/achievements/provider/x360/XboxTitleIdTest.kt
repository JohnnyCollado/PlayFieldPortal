package com.playfieldportal.feature.achievements.provider.x360

import com.playfieldportal.feature.achievements.match.DiscImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Reading an Xbox 360 game's title ID from its own file: a bare XEX, an STFS package (GOD/XBLA),
 * or an XDVDFS disc image holding `default.xex`. The title ID is the GPD file name, so it is what
 * links a library game to its achievements.
 */
class XboxTitleIdTest {

    private class Bytes(private val data: ByteArray) : DiscImage.SeekableSource {
        override fun readFully(offset: Long, dest: ByteArray, len: Int): Int {
            if (offset >= data.size) return -1
            val n = minOf(len.toLong(), data.size - offset).toInt()
            System.arraycopy(data, offset.toInt(), dest, 0, n)
            return n
        }
        override fun close() = Unit
    }

    /** A minimal XEX2 header with one optional header: execution info carrying [titleId]. */
    private fun xex(titleId: Int, extraHeadersBefore: Int = 0): ByteArray {
        val headerCount = extraHeadersBefore + 1
        val execOffset = 0x18 + headerCount * 8
        val buf = ByteBuffer.allocate(execOffset + 0x18)
        buf.put("XEX2".encodeToByteArray()).putInt(0).putInt(0x3000).putInt(0).putInt(0)
            .putInt(headerCount)
        repeat(extraHeadersBefore) { buf.putInt(0x000002FF).putInt(0) }   // some other header
        buf.putInt(XboxTitleId.EXECUTION_INFO_KEY).putInt(execOffset)
        // media id, version, base version, title id, platform, exe type, disc, disc count
        buf.putInt(0x11111111).putInt(0).putInt(0).putInt(titleId)
        buf.put(0).put(0).put(1).put(1).putInt(0)
        return buf.array()
    }

    @Test
    fun `reads the title id from an XEX execution info header`() {
        assertEquals("544307D1", XboxTitleId.fromXex(xex(0x544307D1)))
    }

    @Test
    fun `finds execution info behind other optional headers`() {
        assertEquals("41560855", XboxTitleId.fromXex(xex(0x41560855, extraHeadersBefore = 3)))
    }

    @Test
    fun `an XEX with no execution info has no title id`() {
        val bytes = ByteBuffer.allocate(0x20).put("XEX2".encodeToByteArray()).putInt(0).putInt(0)
            .putInt(0).putInt(0).putInt(0).array()
        assertNull(XboxTitleId.fromXex(bytes))
        assertNull(XboxTitleId.fromXex("nope".encodeToByteArray()))
    }

    @Test
    fun `reads the title id from an STFS package header`() {
        for (magic in listOf("CON ", "LIVE", "PIRS")) {
            val bytes = ByteArray(0x400)
            magic.encodeToByteArray().copyInto(bytes)
            ByteBuffer.wrap(bytes).putInt(0x360, 0x58410A1B)
            assertEquals("58410A1B", XboxTitleId.fromSource(Bytes(bytes)), magic)
        }
    }

    @Test
    fun `reads a bare XEX through the source sniffing`() {
        assertEquals("544307D1", XboxTitleId.fromSource(Bytes(xex(0x544307D1))))
    }

    @Test
    fun `follows an XDVDFS image to default xex at every known partition offset`() {
        for (partition in listOf(0L, 0x2080000L)) {   // trimmed XISO and an XGD3 disc
            val image = xdvdfs(partition, listOf("media.bin" to ByteArray(10), "default.xex" to xex(0x4D5307E6)))
            assertEquals("4D5307E6", XboxTitleId.fromSource(Bytes(image)), "partition $partition")
        }
    }

    @Test
    fun `an XDVDFS image without default xex has no title id`() {
        assertNull(XboxTitleId.fromSource(Bytes(xdvdfs(0, listOf("readme.txt" to ByteArray(4))))))
    }

    @Test
    fun `anything else has no title id`() {
        assertNull(XboxTitleId.fromSource(Bytes(ByteArray(0x20000))))
        assertNull(XboxTitleId.fromSource(Bytes(ByteArray(0))))
    }

    /**
     * An XDVDFS image: the volume descriptor at sector 32 of the partition, a root directory at
     * sector 33 listing [files] (linear entries, 4-byte aligned), and each file from sector 34 on.
     */
    private fun xdvdfs(partition: Long, files: List<Pair<String, ByteArray>>): ByteArray {
        val sector = 2048
        val dirSector = 33
        val firstFileSector = 34
        val size = partition + (firstFileSector + files.size + 1).toLong() * sector
        val image = ByteArray(size.toInt())
        fun at(s: Int) = (partition + s.toLong() * sector).toInt()

        val vd = ByteBuffer.wrap(image, at(32), sector).order(ByteOrder.LITTLE_ENDIAN)
        vd.put("MICROSOFT*XBOX*MEDIA".encodeToByteArray()).putInt(dirSector).putInt(sector)

        val dir = ByteBuffer.wrap(image, at(dirSector), sector).order(ByteOrder.LITTLE_ENDIAN)
        files.forEachIndexed { i, (name, data) ->
            val start = dir.position()
            dir.putShort(0).putShort(0).putInt(firstFileSector + i).putInt(data.size)
                .put(0x80.toByte()).put(name.length.toByte()).put(name.encodeToByteArray())
            while ((dir.position() - start) % 4 != 0) dir.put(0xFF.toByte())
            data.copyInto(image, at(firstFileSector + i))
        }
        // The rest of the directory sector is 0xFF padding, as on a real disc.
        while (dir.hasRemaining()) dir.put(0xFF.toByte())
        return image
    }
}
