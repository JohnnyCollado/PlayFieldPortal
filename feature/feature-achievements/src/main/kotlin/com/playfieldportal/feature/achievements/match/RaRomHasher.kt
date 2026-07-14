package com.playfieldportal.feature.achievements.match

import java.security.MessageDigest

/**
 * Computes a ROM's RetroAchievements content hash. RA identifies games by a console-specific hash:
 * most cartridge systems use a plain MD5 of the file, but a few strip a header (NES, SNES) or
 * normalize byte order (N64) first. Disc and arcade systems use a different scheme and are not
 * handled here — those return null and fall to the manual / unmatched path.
 *
 * Cartridge-first subset. See docs/shiba-coins-achievements-plan.md.
 */
object RaRomHasher {

    private enum class Method { FULL, NES, SNES, N64, NDS, UNSUPPORTED }

    private fun methodFor(platformId: String): Method = when (platformId) {
        "nes" -> Method.NES
        "snes" -> Method.SNES
        "n64" -> Method.N64
        "nds" -> Method.NDS
        "gb", "gbc", "gba", "megadrive", "mastersystem", "gamegear", "sega32x",
        "pcengine", "virtualboy", "ngp", "wonderswan", "wonderswancolor",
        "atari2600", "atari7800", "atarilynx" -> Method.FULL
        else -> Method.UNSUPPORTED
    }

    /** True when this platform has a supported cartridge hash. */
    fun isSupported(platformId: String): Boolean = methodFor(platformId) != Method.UNSUPPORTED

    /** Lowercase-hex MD5 of the RA content hash, or null for an unsupported platform. */
    fun hash(platformId: String, bytes: ByteArray): String? = when (methodFor(platformId)) {
        Method.FULL -> md5Hex(bytes)
        Method.NES -> md5Hex(stripNesHeader(bytes))
        Method.SNES -> md5Hex(stripSnesHeader(bytes))
        Method.N64 -> md5Hex(normalizeN64(bytes))
        Method.NDS -> hashNds(bytes)
        Method.UNSUPPORTED -> null
    }

    // NDS: MD5 of the 0x160 header + the ARM9 and ARM7 boot code + the 0xA00-byte icon/title block.
    // Offsets/sizes are u32 little-endian in the header. Matches rcheevos rc_hash_nintendo_ds
    // (hashed as-is; the encrypted ARM9 secure area is included, so standard dumps match).
    private fun hashNds(b: ByteArray): String? {
        if (b.size < 0x160) return null
        val arm9Off = u32le(b, 0x20)
        val arm9Size = u32le(b, 0x2C)
        val arm7Off = u32le(b, 0x30)
        val arm7Size = u32le(b, 0x3C)
        val iconOff = u32le(b, 0x68)
        if (arm9Size < 0 || arm7Size < 0 || arm9Size.toLong() + arm7Size > 16L * 1024 * 1024) return null

        val md = MessageDigest.getInstance("MD5")
        md.update(b, 0, 0x160)
        appendRegion(md, b, arm9Off, arm9Size)
        appendRegion(md, b, arm7Off, arm7Size)
        appendPadded(md, b, iconOff, 0xA00)
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /**
     * NDS hash from a seekable source, reading only the header + arm9 + arm7 + icon regions instead
     * of loading the whole (up to 256 MB) cart into memory — the byte-for-byte identical hash to
     * [hashNds] (ByteArray), but without the OOM risk on large ROMs. Regions that run past EOF are
     * clamped / zero-padded exactly as the in-memory path does.
     */
    fun hashNds(source: DiscImage.SeekableSource): String? {
        val header = readAt(source, 0, 0x160)
        if (header.size < 0x160) return null
        val arm9Off = u32le(header, 0x20).toLong() and 0xFFFFFFFFL
        val arm9Size = u32le(header, 0x2C)
        val arm7Off = u32le(header, 0x30).toLong() and 0xFFFFFFFFL
        val arm7Size = u32le(header, 0x3C)
        val iconOff = u32le(header, 0x68).toLong() and 0xFFFFFFFFL
        if (arm9Size < 0 || arm7Size < 0 || arm9Size.toLong() + arm7Size > 16L * 1024 * 1024) return null

        val md = MessageDigest.getInstance("MD5")
        md.update(header, 0, 0x160)
        appendSeek(md, source, arm9Off, arm9Size)
        appendSeek(md, source, arm7Off, arm7Size)
        appendSeekPadded(md, source, iconOff, 0xA00)
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun readAt(source: DiscImage.SeekableSource, off: Long, len: Int): ByteArray {
        val b = ByteArray(len)
        val n = source.readFully(off, b, len)
        return if (n == len) b else b.copyOf(maxOf(n, 0))
    }

    // Streams [size] bytes from [off] into [md] in 64 KB chunks (arm9/arm7 code blocks), stopping at
    // EOF — matches the in-memory [appendRegion] clamp without holding the region in one allocation.
    private fun appendSeek(md: MessageDigest, source: DiscImage.SeekableSource, off: Long, size: Int) {
        if (off < 0 || size <= 0) return
        val buf = ByteArray(minOf(size, 1 shl 16))
        var remaining = size
        var pos = off
        while (remaining > 0) {
            val want = minOf(remaining, buf.size)
            val n = source.readFully(pos, buf, want)
            if (n <= 0) break
            md.update(buf, 0, n)
            remaining -= n
            pos += n
            if (n < want) break
        }
    }

    // Hashes exactly [size] bytes from [off], zero-padded past EOF (the 0xA00 icon block).
    private fun appendSeekPadded(md: MessageDigest, source: DiscImage.SeekableSource, off: Long, size: Int) {
        val chunk = ByteArray(size)
        if (off >= 0) source.readFully(off, chunk, size) // leftover bytes stay zero
        md.update(chunk)
    }

    private fun u32le(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    // Hashes [size] bytes from [off], bounded to the ROM (a valid header points inside the file).
    private fun appendRegion(md: MessageDigest, b: ByteArray, off: Int, size: Int) {
        if (off < 0 || size <= 0 || off >= b.size) return
        md.update(b, off, minOf(off.toLong() + size, b.size.toLong()).toInt() - off)
    }

    // Hashes exactly [size] bytes from [off], zero-padded when the ROM is shorter (icon block).
    private fun appendPadded(md: MessageDigest, b: ByteArray, off: Int, size: Int) {
        val chunk = ByteArray(size)
        if (off in 0 until b.size) System.arraycopy(b, off, chunk, 0, minOf(size, b.size - off))
        md.update(chunk)
    }

    // NES: the 16-byte iNES header ("NES...") is not part of the content.
    private fun stripNesHeader(b: ByteArray): ByteArray =
        if (b.size > 16 && b[0] == 'N'.code.toByte() && b[1] == 'E'.code.toByte() &&
            b[2] == 'S'.code.toByte() && b[3] == 0x1A.toByte()
        ) b.copyOfRange(16, b.size) else b

    // SNES: a 512-byte copier header is present iff the size leaves a 512-byte remainder mod 1024.
    private fun stripSnesHeader(b: ByteArray): ByteArray =
        if (b.size % 1024 == 512) b.copyOfRange(512, b.size) else b

    // N64: normalize the three dump formats to big-endian (z64) so they hash identically.
    private fun normalizeN64(b: ByteArray): ByteArray {
        if (b.size < 4) return b
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        return when {
            b0 == 0x80 && b1 == 0x37 -> b            // z64 (big-endian, native)
            b0 == 0x37 && b1 == 0x80 -> swap16(b)    // v64 (byte-swapped)
            b0 == 0x40 && b1 == 0x12 -> swap32(b)    // n64 (little-endian)
            else -> b
        }
    }

    private fun swap16(b: ByteArray): ByteArray {
        val out = b.copyOf()
        var i = 0
        while (i + 1 < out.size) {
            val t = out[i]; out[i] = out[i + 1]; out[i + 1] = t
            i += 2
        }
        return out
    }

    private fun swap32(b: ByteArray): ByteArray {
        val out = b.copyOf()
        var i = 0
        while (i + 3 < out.size) {
            val a = out[i]; val c = out[i + 1]
            out[i] = out[i + 3]; out[i + 1] = out[i + 2]; out[i + 2] = c; out[i + 3] = a
            i += 4
        }
        return out
    }

    private fun md5Hex(b: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/**
 * Maps a PFP platform id to its RetroAchievements console id (for the game/hash list lookup). RA is
 * hash-only, so a console appears here only when some hasher can produce its content hash:
 * cartridges via [RaRomHasher]; PSX/PS2/PSP via [RaDiscHasher]; Sega CD/Saturn via [RaSegaDiscHasher];
 * GameCube/Wii via [RaNintendoDiscHasher]; Dreamcast (GDI) via [RaDreamcastHasher]. Systems RA has no
 * achievements for (PS3, Wii U, Vita, 3DS, Xbox 360) are deliberately absent.
 */
object RaConsole {
    fun idFor(platformId: String): Int? = when (platformId) {
        // Cartridge / handheld (hashed by RaRomHasher)
        "megadrive" -> 1
        "n64" -> 2
        "snes" -> 3
        "nds" -> 18
        "gb" -> 4
        "gba" -> 5
        "gbc" -> 6
        "nes" -> 7
        "pcengine" -> 8
        "sega32x" -> 10
        "mastersystem" -> 11
        "atarilynx" -> 13
        "ngp" -> 14
        "gamegear" -> 15
        "atari2600" -> 25
        "virtualboy" -> 28
        "atari7800" -> 51
        "wonderswan" -> 53
        // Disc-based (hashed by RaDiscHasher / RaSegaDiscHasher / RaNintendoDiscHasher / RaDreamcastHasher)
        "segacd" -> 9
        "psx" -> 12
        "gc" -> 16
        "wii" -> 19
        "ps2" -> 21
        "saturn" -> 39
        "dreamcast" -> 40
        "psp" -> 41
        else -> null
    }
}
