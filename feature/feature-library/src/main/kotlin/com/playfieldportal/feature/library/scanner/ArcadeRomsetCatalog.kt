package com.playfieldportal.feature.library.scanner

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Validated arcade romset database, ES-DE style: an allowlist of recognised romset names mapped to
 * the specific system they belong to (cps1/cps2/cps3/neogeo, else generic mame) plus their full
 * game name. Built from the FBAlpha gamelist, console cores and BIOS/device sets removed (see
 * assets/arcade/arcade_romsets.tsv).
 *
 * Purpose: a folder of arcade `.zip` files is full of ambiguity — BIOS/device sets, non-game
 * archives, and CPS/Neo Geo/other titles all mixed together with generic "arcade" extensions.
 * Instead of turning every `.zip` into a library entry, the scanner asks this catalog to
 * (a) recognise valid romsets, (b) route them to the correct system regardless of which folder they
 * sit in, and (c) title them properly.
 *
 * Strictness differs by system. CPS1/2/3 and Neo Geo are fully covered by FBNeo, so a `.zip` filed
 * under one of those platforms that isn't a known romset is treated as invalid/incomplete and
 * skipped. Generic MAME is NOT strict — MAME's library far exceeds FBNeo's, so an unrecognised zip
 * in a MAME folder is kept (named from its filename) rather than dropped.
 */
@Singleton
class ArcadeRomsetCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class Entry(val platformId: String, val title: String)

    /** Fully-covered systems — a non-catalogued zip filed here is skipped as an invalid set. */
    val validatedPlatforms = setOf("cps1", "cps2", "cps3", "neogeo")

    // Only these folder contexts get romset routing/validation. A .zip resolved to a console
    // platform (a SNES/Genesis game archived as .zip) is left alone even if its name happens to
    // collide with an arcade romset — routing is an arcade-folder concern only.
    private val arcadePlatforms = validatedPlatforms + setOf("mame", "arcade")

    // romset name (lowercase, no extension) → entry
    private val byRomset: Map<String, Entry> by lazy { load() }

    private fun load(): Map<String, Entry> {
        val out = HashMap<String, Entry>()
        runCatching {
            context.assets.open("arcade/arcade_romsets.tsv").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val parts = line.split('\t')
                    if (parts.size < 3) continue
                    out[parts[0].trim().lowercase()] = Entry(parts[1].trim(), parts[2].trim())
                }
            }
        }.onFailure { Timber.e(it, "Failed to load arcade romset catalog") }
        Timber.i("Arcade romset catalog loaded: ${out.size} romsets (cps1/2/3, neogeo, mame)")
        return out
    }

    /** Decision for one archive file during a scan. */
    sealed interface Decision {
        /** Not a catalogued arcade file — the scanner keeps its normal platform + filename title. */
        data object UseDefault : Decision
        /** A recognised CPS romset — file it under [platformId] with the proper [title]. */
        data class Route(val platformId: String, val title: String) : Decision
        /** A `.zip`/`.7z` filed under a CPS platform that isn't a known romset — skip it. */
        data object Skip : Decision
    }

    /**
     * @param fileStem file name without extension (e.g. "sfiii3")
     * @param extensionLower lowercase extension without dot (e.g. "zip")
     * @param candidatePlatformId the platform the scanner would otherwise assign (from folder/ext)
     */
    fun decide(fileStem: String, extensionLower: String, candidatePlatformId: String?): Decision {
        if (extensionLower != "zip" && extensionLower != "7z") return Decision.UseDefault
        if (candidatePlatformId !in arcadePlatforms) return Decision.UseDefault
        byRomset[fileStem.lowercase()]?.let { return Decision.Route(it.platformId, it.title) }
        return if (candidatePlatformId in validatedPlatforms) Decision.Skip else Decision.UseDefault
    }
}
