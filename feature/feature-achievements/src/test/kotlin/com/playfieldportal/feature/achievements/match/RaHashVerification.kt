package com.playfieldportal.feature.achievements.match

import com.playfieldportal.core.domain.model.Game
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Desktop verification harness for the RetroAchievements content hashers.
 *
 * The other tests in this package prove the hashers are internally consistent over synthetic images.
 * This one runs them over REAL disc/ROM files and prints the computed hash so it can be diffed against
 * the ground truth from rcheevos (`rhasher <system> <file>`), RetroArch's load log, or a game's
 * "Supported Game Files" list on retroachievements.org. It reuses the exact production entry points
 * (mirroring [AchievementAutoMatcher.attemptHash], including the real `.gdi` parser), so a match here
 * means our transcription reproduces RA's hash byte-for-byte.
 *
 * It is inert in a normal test run — with no input it returns immediately (green). To use it, point
 * it at a manifest via an env var (inherited automatically) or a -D flag (forwarded by this module's
 * build.gradle.kts):
 *
 *   RA_HASH_MANIFEST=/path/to/manifest.txt ./gradlew :feature:feature-achievements:testDebugUnitTest \
 *       --tests '*RaHashVerification' --rerun
 *   # or:  ./gradlew ... --tests '*RaHashVerification' -Dra.hash.manifest=/path/to/manifest.txt --rerun
 *
 * The manifest is one entry per line, pipe-separated, `#` for comments:
 *
 *   # platformId | path/to/file | expectedHash (optional)
 *   gc        | /roms/gc/Zelda.iso            | 4f2b...   <- known RA hash: pass/fail
 *   wii       | /roms/wii/Galaxy.iso                       <- no expected: just prints ours
 *   segacd    | /roms/segacd/Sonic.cue
 *   saturn    | /roms/saturn/NiGHTS.bin
 *   dreamcast | /roms/dc/Crazy Taxi.gdi       | abcd...
 *   snes      | /roms/snes/Chrono Trigger.sfc
 *
 * Or inline (entries separated by `;`): -Dra.hash.spec='gc|/a.iso|hash;wii|/b.iso'.
 *
 * Results are printed and written to a report file (`-Dra.hash.out`, default
 * `build/ra-hash-verification.txt`). When an expected hash is supplied and any entry mismatches (or a
 * file can't be hashed), the test FAILS — so once you paste RA's hashes, a green run is verification.
 * Zip / compressed containers (NKit, RVZ, CISO, WBFS, CHD) aren't expanded here; point at raw images.
 */
class RaHashVerification {

    private data class Spec(val platformId: String, val path: String, val expected: String?)

    private data class Outcome(val spec: Spec, val actual: String?, val note: String)

    @Test
    fun `verify RA hashes against real files when a manifest is supplied`() {
        val specs = readSpecs() ?: return // no input -> inert, passes
        if (specs.isEmpty()) return

        val outcomes = specs.map { evaluate(it) }
        val report = buildReport(outcomes)

        println(report)
        val out = outputFile()
        runCatching { out.apply { parentFile?.mkdirs() }.writeText(report) }
            .onSuccess { println("Wrote report to ${out.absolutePath}") }

        val failures = outcomes.filter { it.spec.expected != null && !it.note.startsWith("MATCH") }
        if (failures.isNotEmpty()) {
            fail("${failures.size} hash verification(s) failed:\n" +
                failures.joinToString("\n") { "  ${it.spec.platformId}: ${it.note} (${it.spec.path})" })
        }
    }

    // ── evaluation ──────────────────────────────────────────────────────────────

    private fun evaluate(spec: Spec): Outcome {
        val file = File(spec.path)
        if (!file.exists()) return Outcome(spec, null, "MISSING FILE")
        val actual = runCatching { computeHash(spec.platformId, file) }
            .getOrElse { return Outcome(spec, null, "ERROR: ${it.message}") }
            ?: return Outcome(spec, null, "NOT HASHABLE (unsupported platform or unidentified image)")

        val note = when {
            spec.expected == null -> "computed"
            spec.expected.equals(actual, ignoreCase = true) -> "MATCH"
            else -> "MISMATCH (expected ${spec.expected.lowercase()})"
        }
        return Outcome(spec, actual, note)
    }

    // Mirrors AchievementAutoMatcher.attemptHash, minus the Android SAF plumbing (point at real paths).
    private fun computeHash(platformId: String, file: File): String? {
        // CHD container: decompresses to logical sectors, feeding the CD/GD-ROM hashers.
        if (file.extension.equals("chd", ignoreCase = true)) {
            val chd = ChdReader.open(DiscImage.rawSource(file)) ?: return null
            val sectors = ChdSectorSource.of(chd) ?: run { chd.close(); return null }
            return DiscImage.openTracks(sectors, sectors.firstTrackSector).use { img ->
                when {
                    RaSegaDiscHasher.isSupported(platformId) -> RaSegaDiscHasher.hash(img)
                    RaDreamcastHasher.isSupported(platformId) -> RaDreamcastHasher.hash(img)
                    RaDiscHasher.isSupported(platformId) -> RaDiscHasher.hash(platformId, img)
                    else -> null
                }
            }
        }
        return when {
        RaRomHasher.isSupported(platformId) ->
            // NDS uses the seeking reader (mirrors the auto-matcher), avoiding a full-ROM load.
            if (platformId == "nds") DiscImage.rawSource(file).use { RaRomHasher.hashNds(it) }
            else RaRomHasher.hash(platformId, file.readBytes())

        RaNintendoDiscHasher.isSupported(platformId) ->
            DiscImage.rawSource(file).use { RaNintendoDiscHasher.hash(platformId, it) }

        RaSegaDiscHasher.isSupported(platformId) ->
            DiscImage.openRawCd(DiscImage.rawSource(file)).use { RaSegaDiscHasher.hash(it) }

        RaDreamcastHasher.isSupported(platformId) -> {
            // Reuse the real .gdi parser; its Context is only touched on the SAF path, not here.
            val game = Game(id = 0, title = file.name, platformId = platformId, romPath = file.absolutePath)
            runBlocking { DiscImageOpener(mockk(relaxed = true)).openGdi(game) }
                ?.use { RaDreamcastHasher.hash(it) }
        }

        RaDiscHasher.isSupported(platformId) ->
            DiscImage.open(file)?.use { RaDiscHasher.hash(platformId, it) }

        else -> null
        }
    }

    // ── input / output ──────────────────────────────────────────────────────────

    // Env var wins (inherited by the forked test JVM automatically); the -D property is forwarded by
    // the module's build.gradle.kts testOptions block.
    private fun config(sysKey: String, envKey: String): String? =
        System.getenv(envKey)?.takeIf { it.isNotBlank() } ?: System.getProperty(sysKey)?.takeIf { it.isNotBlank() }

    private fun readSpecs(): List<Spec>? {
        val manifest = config("ra.hash.manifest", "RA_HASH_MANIFEST")?.let(::File)?.takeIf(File::exists)
        val lines: List<String> = when {
            manifest != null -> manifest.readLines()
            else -> config("ra.hash.spec", "RA_HASH_SPEC")?.split(';') ?: return null
        }
        return lines
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { parseLine(it) }
    }

    private fun parseLine(line: String): Spec? {
        val parts = line.split('|').map { it.trim() }
        if (parts.size < 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null
        return Spec(parts[0], parts[1], parts.getOrNull(2)?.takeIf { it.isNotEmpty() })
    }

    private fun outputFile(): File =
        File(config("ra.hash.out", "RA_HASH_OUT") ?: "build/ra-hash-verification.txt")

    private fun buildReport(outcomes: List<Outcome>): String = buildString {
        appendLine("RetroAchievements hash verification")
        appendLine("=".repeat(72))
        outcomes.forEach { o ->
            appendLine("platform : ${o.spec.platformId}")
            appendLine("file     : ${o.spec.path}")
            appendLine("computed : ${o.actual ?: "-"}")
            if (o.spec.expected != null) appendLine("expected : ${o.spec.expected.lowercase()}")
            appendLine("result   : ${o.note}")
            appendLine("-".repeat(72))
        }
        val matched = outcomes.count { it.note == "MATCH" }
        val checked = outcomes.count { it.spec.expected != null }
        if (checked > 0) appendLine("Verified $matched/$checked against expected hashes.")
    }
}
