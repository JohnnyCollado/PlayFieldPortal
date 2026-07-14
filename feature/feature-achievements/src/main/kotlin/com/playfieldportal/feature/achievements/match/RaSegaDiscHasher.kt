package com.playfieldportal.feature.achievements.match

import java.security.MessageDigest

/**
 * RetroAchievements content hashing for the Sega CD and Sega Saturn, transcribed from rcheevos
 * rc_hash_sega_cd (src/rhash/hash_disc.c). Both systems store a volume + ROM header at the very
 * start of the first data track; RA hashes the first 512 bytes of sector 0 and trusts that as the
 * game's identity (there is no single primary executable to key off). The two systems share the
 * routine and differ only by the header's magic string. See docs/shiba-coins-achievements-plan.md.
 */
object RaSegaDiscHasher {

    private const val HEADER_SIZE = 512
    private val SEGA_CD = "SEGADISCSYSTEM  ".toByteArray(Charsets.US_ASCII) // 16 bytes
    private val SATURN = "SEGA SEGASATURN ".toByteArray(Charsets.US_ASCII) // 16 bytes

    /** True when this platform hashes as a Sega CD / Saturn disc header. */
    fun isSupported(platformId: String): Boolean = platformId == "segacd" || platformId == "saturn"

    /** Lowercase-hex MD5 of the 512-byte boot header, or null if [image] isn't a Sega CD/Saturn disc. */
    fun hash(image: DiscImage): String? {
        val header = image.readSector(0, HEADER_SIZE)
        if (header.size < HEADER_SIZE) return null
        if (!startsWith(header, SEGA_CD) && !startsWith(header, SATURN)) return null
        val md5 = MessageDigest.getInstance("MD5")
        md5.update(header, 0, HEADER_SIZE)
        return md5.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun startsWith(b: ByteArray, magic: ByteArray): Boolean {
        if (b.size < magic.size) return false
        for (i in magic.indices) if (b[i] != magic[i]) return false
        return true
    }
}
