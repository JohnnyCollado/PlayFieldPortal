package com.playfieldportal.feature.achievements.match

import java.security.MessageDigest

/**
 * RetroAchievements content hashing for the Sega Dreamcast, transcribed from rcheevos
 * rc_hash_dreamcast (src/rhash/hash_disc.c). A GD-ROM identifies itself by IP.BIN — the 256-byte
 * boot header at the start of the data track — plus its boot executable. RA hashes the 256-byte
 * IP.BIN followed by the boot file's contents.
 *
 * The multi-track GD-ROM addressing (IP.BIN in track 3, ISO9660 relative to that track's base LBA,
 * boot file possibly in a later track) is handled by [GdiTrackSource] / [DiscImage.openTracks]; this
 * hasher works purely in logical sectors. See docs/shiba-coins-achievements-plan.md.
 */
object RaDreamcastHasher {

    private const val IP_BIN_SIZE = 256
    private const val BOOT_NAME_OFFSET = 96
    private const val BOOT_NAME_MAX = 16
    private val KATANA = "SEGA SEGAKATANA ".toByteArray(Charsets.US_ASCII) // 16 bytes

    /** True when this platform hashes as a Dreamcast GD-ROM. */
    fun isSupported(platformId: String): Boolean = platformId == "dreamcast"

    /** True when [bytes] begins with the Dreamcast IP.BIN boot header. */
    fun isDreamcastHeader(bytes: ByteArray): Boolean {
        if (bytes.size < KATANA.size) return false
        for (i in KATANA.indices) if (bytes[i] != KATANA[i]) return false
        return true
    }

    /**
     * Lowercase-hex MD5 of IP.BIN + the boot executable's contents, or null if [image] isn't a
     * Dreamcast disc or its boot file can't be located. [image] must be opened at the IP.BIN track
     * ([DiscImage.firstTrackSector]).
     */
    fun hash(image: DiscImage): String? {
        val ip = image.readSector(image.firstTrackSector, IP_BIN_SIZE)
        if (ip.size < IP_BIN_SIZE || !isDreamcastHeader(ip)) return null

        val md5 = MessageDigest.getInstance("MD5")
        md5.update(ip, 0, IP_BIN_SIZE)

        val bootName = bootName(ip) ?: return null
        val entry = image.findFile(bootName) ?: return null
        image.hashFileInto(md5, entry.lba, entry.size)
        return md5.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    // The boot filename is up to 16 bytes at offset 96 of IP.BIN, terminated by whitespace (the field
    // is space-padded). Matches rcheevos' isspace()-terminated read.
    private fun bootName(ip: ByteArray): String? {
        val sb = StringBuilder()
        var i = 0
        while (i < BOOT_NAME_MAX) {
            val c = ip[BOOT_NAME_OFFSET + i].toInt() and 0xFF
            if (isSpace(c)) break
            sb.append(c.toChar())
            i++
        }
        return sb.toString().ifEmpty { null }
    }

    private fun isSpace(c: Int): Boolean = c == 0x20 || c in 0x09..0x0D
}
