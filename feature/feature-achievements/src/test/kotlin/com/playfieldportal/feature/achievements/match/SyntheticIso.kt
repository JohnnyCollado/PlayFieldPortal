package com.playfieldportal.feature.achievements.match

/**
 * Builds a minimal cooked (2048-byte-sector) ISO9660 image in memory for hasher tests: a PVD at
 * sector 16, a root directory at sector 17, and one sector per subdirectory / file. Only the fields
 * [DiscImage] reads are populated (extent LBA, data length, id) — enough to exercise the real
 * directory traversal and file hashing without a multi-GB fixture.
 */
class SyntheticIso {
    private val dirs = linkedSetOf("")           // "" = root
    private val files = linkedMapOf<String, ByteArray>()

    fun addDir(path: String): SyntheticIso { ensureParents(path); dirs.add(path); return this }

    fun addFile(path: String, bytes: ByteArray): SyntheticIso {
        ensureParents(path); files[path] = bytes; return this
    }

    private fun ensureParents(path: String) {
        val parent = parentOf(path)
        if (parent.isNotEmpty()) { ensureParents(parent); dirs.add(parent) }
    }

    /**
     * Builds the image. Physical placement is compact (sector 16 = PVD, 17 = root, then dirs/files),
     * but the LBA fields written into the ISO9660 records are offset by [baseLba] — modelling a GD-ROM
     * whose filesystem sits on a track that starts deep in the disc (Dreamcast track 3 at LBA 45000),
     * where records address sectors absolutely. Zero (the default) yields a normal single-track image.
     */
    fun build(baseLba: Int = 0): ByteArray {
        val lba = HashMap<String, Int>()
        val size = HashMap<String, Int>()

        var next = 17
        lba[""] = next++ // root directory
        for (d in dirs) if (d.isNotEmpty()) { lba[d] = next++; size[d] = SECTOR }
        for ((f, bytes) in files) { lba[f] = next; size[f] = bytes.size; next += sectorsFor(bytes.size) }

        val image = ByteArray(next * SECTOR)

        // PVD at sector 16.
        val pvd = 16 * SECTOR
        image[pvd] = 0x01
        "CD001".toByteArray(Charsets.US_ASCII).copyInto(image, pvd + 1)
        image[pvd + 6] = 0x01
        putLE(image, pvd + 128, SECTOR, 2)                 // logical block size
        writeDirRecord(image, pvd + 156, lba[""]!! + baseLba, SECTOR, id = byteArrayOf(0), isDir = true)

        // Directory sectors: each dir's immediate children, back to back (zero byte terminates).
        for (dir in dirs) {
            var off = lba[dir]!! * SECTOR
            for (child in childrenOf(dir)) {
                val isDir = child in dirs
                val idText = if (isDir) nameOf(child) else "${nameOf(child)};1"
                off += writeDirRecord(image, off, lba[child]!! + baseLba, size[child]!!, idText.toByteArray(Charsets.US_ASCII), isDir)
            }
        }

        // File contents (placed at their compact physical sector).
        for ((f, bytes) in files) bytes.copyInto(image, lba[f]!! * SECTOR)
        return image
    }

    private fun childrenOf(dir: String): List<String> =
        (dirs + files.keys).filter { it.isNotEmpty() && parentOf(it) == dir }

    // Writes an ISO9660 directory record; returns its (even-padded) length.
    private fun writeDirRecord(b: ByteArray, off: Int, lba: Int, len: Int, id: ByteArray, isDir: Boolean): Int {
        var recLen = 33 + id.size
        if (recLen and 1 == 1) recLen++
        b[off] = recLen.toByte()
        putBothEndian(b, off + 2, lba)
        putBothEndian(b, off + 10, len)
        b[off + 25] = if (isDir) 0x02 else 0x00
        putBothEndian(b, off + 28, 1)                      // volume sequence number
        b[off + 32] = id.size.toByte()
        id.copyInto(b, off + 33)
        return recLen
    }

    private fun putLE(b: ByteArray, off: Int, v: Int, bytes: Int) {
        for (k in 0 until bytes) b[off + k] = ((v shr (8 * k)) and 0xFF).toByte()
    }

    // ISO9660 stores many integers both little- and big-endian back to back.
    private fun putBothEndian(b: ByteArray, off: Int, v: Int) {
        putLE(b, off, v, 4)
        for (k in 0 until 4) b[off + 4 + k] = ((v shr (8 * (3 - k))) and 0xFF).toByte()
    }

    private fun parentOf(path: String) = path.substringBeforeLast('\\', "")
    private fun nameOf(path: String) = path.substringAfterLast('\\')
    private fun sectorsFor(bytes: Int) = maxOf(1, (bytes + SECTOR - 1) / SECTOR)

    private companion object { const val SECTOR = 2048 }
}
