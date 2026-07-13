package com.playfieldportal.feature.achievements.match

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URL
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Local verification against a real ROM library — deliberately NOT a portable CI test. It is
 * skipped unless the ROM root exists (default `F:/Backups/Roms`; override with `PFP_ROM_ROOT`),
 * so it no-ops on any other machine.
 *
 * It confirms the cartridge hashers reproduce RA's exact hashes on real files, and it computes the
 * Nintendo DS hashes. When `RA_USER` and `RA_KEY` are set in the environment it cross-checks those
 * NDS hashes against RetroAchievements' live hash list — so you can confirm matching BEFORE
 * installing on-device. No ROM bytes and no API key are ever committed.
 */
class RomLibraryVerificationTest {

    private val root = File(System.getenv("PFP_ROM_ROOT") ?: "F:/Backups/Roms")

    private fun md5(b: ByteArray) =
        MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun `cartridge hashers reproduce RA hashes on real roms`() {
        assumeTrue("no ROM library at $root", root.exists())

        // Full-MD5 systems: RA's hash is simply the file MD5.
        for (sys in listOf("gba", "gb")) {
            File(root, sys).listFiles { f -> f.extension.lowercase() in setOf("gba", "gb") }?.forEach { f ->
                val b = f.readBytes()
                assertEquals(md5(b), RaRomHasher.hash(sys, b), "full-md5 $sys/${f.name}")
            }
        }
        // NES: RA strips the 16-byte iNES header before MD5.
        File(root, "nes").listFiles { f -> f.extension.equals("nes", true) }?.forEach { f ->
            val b = f.readBytes()
            val hasHeader = b.size > 16 && b[0] == 'N'.code.toByte() && b[3] == 0x1A.toByte()
            val expected = if (hasHeader) md5(b.copyOfRange(16, b.size)) else md5(b)
            assertEquals(expected, RaRomHasher.hash("nes", b), "nes/${f.name}")
        }
    }

    @Test
    fun `nds hashes are computed and, with RA creds, verified against the live database`() {
        assumeTrue("no ROM library at $root", root.exists())
        val ndsDir = File(root, "nds")
        assumeTrue("no nds folder", ndsDir.isDirectory)
        val roms = ndsDir.listFiles { f -> f.extension.equals("nds", true) }?.sortedBy { it.name } ?: emptyList()
        assumeTrue("no nds roms", roms.isNotEmpty())

        val raHashes = fetchRaHashes(consoleId = 18)
        var matched = 0
        for (f in roms) {
            val hash = RaRomHasher.hash("nds", f.readBytes())
            val status = when {
                raHashes == null -> "(set RA_USER/RA_KEY to verify)"
                hash != null && hash in raHashes -> { matched++; "MATCH" }
                else -> "no RA match"
            }
            println("NDS  $hash  ${f.name}  $status")
        }
        if (raHashes != null) {
            println("NDS matched against RA: $matched / ${roms.size}")
            assertTrue(matched > 0, "expected at least one NDS ROM to match RA")
        }
    }

    // RA's known hashes for a console, or null when credentials aren't in the environment.
    private fun fetchRaHashes(consoleId: Int): Set<String>? {
        val user = System.getenv("RA_USER")?.takeIf { it.isNotBlank() } ?: return null
        val key = System.getenv("RA_KEY")?.takeIf { it.isNotBlank() } ?: return null
        val url = "https://retroachievements.org/API/API_GetGameList.php?z=$user&y=$key&i=$consoleId&h=1&f=1"
        val body = URL(url).readText()
        return Json { ignoreUnknownKeys = true }
            .decodeFromString<List<RaEntry>>(body)
            .flatMap { it.Hashes }
            .map { it.lowercase() }
            .toSet()
    }

    @Serializable
    private data class RaEntry(val Hashes: List<String> = emptyList())
}
