package com.playfieldportal.feature.launcher

import timber.log.Timber
import java.io.File

internal data class RetroArchCore(
    val name: String,           // human-readable label derived from filename
    val fileName: String,       // e.g. "nestopia_libretro_android.so"
    val absolutePath: String,
    val platformIds: List<String>,
)

internal object RetroArchCoreScanner {

    val RETROARCH_PACKAGES = listOf(
        "com.retroarch.aarch64",
        "com.retroarch.ra64",
        "com.retroarch",
        "com.retroarch.ra32",
    )

    // Legacy/custom setups only. Modern RetroArch keeps cores in its private internal dir
    // (/data/user/0/<pkg>/cores), which no other app can enumerate — and Android blocks
    // dlopen() of a .so from shared storage, so cores can never actually live here on a
    // working install. Scanned purely to discover EXTRA core filenames; see [coresFor].
    private val CORE_DIRS = listOf(
        "/storage/emulated/0/RetroArch/cores",
        "/sdcard/RetroArch/cores",
    )

    private data class CuratedCore(
        val fileName: String,
        val name: String,
        val platformIds: List<String>,
    )

    /**
     * One recommended core per system, matching RetroArch's Core Downloader names. PFP cannot
     * verify a core is installed (private storage), so these are offered unconditionally and
     * RetroArch reports the failure itself if the core is missing — the profile's `notes` tells
     * the user which core to download.
     */
    private val CURATED_CORES = listOf(
        CuratedCore("mesen_libretro_android.so",            "Mesen (NES)",              listOf("nes", "fam")),
        CuratedCore("snes9x_libretro_android.so",           "Snes9x (SNES)",            listOf("snes")),
        CuratedCore("mupen64plus_next_libretro_android.so", "Mupen64Plus-Next (N64)",   listOf("n64")),
        CuratedCore("gambatte_libretro_android.so",         "Gambatte (GB/GBC)",        listOf("gb", "gbc")),
        CuratedCore("mgba_libretro_android.so",             "mGBA (GBA)",               listOf("gba", "gb", "gbc")),
        CuratedCore("genesis_plus_gx_libretro_android.so",  "Genesis Plus GX",          listOf("megadrive", "genesis", "mastersystem", "sms", "gamegear", "segacd")),
        CuratedCore("picodrive_libretro_android.so",        "PicoDrive (32X)",          listOf("sega32x", "megadrive", "genesis")),
        CuratedCore("mednafen_saturn_libretro_android.so",  "Beetle Saturn",            listOf("saturn")),
        CuratedCore("mednafen_psx_hw_libretro_android.so",  "Beetle PSX HW",            listOf("psx", "ps1")),
        CuratedCore("mednafen_pce_libretro_android.so",     "Beetle PCE",               listOf("pcengine", "pce", "tgfx16")),
        CuratedCore("fbneo_libretro_android.so",            "FinalBurn Neo (Arcade)",   listOf("arcade", "neogeo", "mame", "cps1", "cps2", "cps3")),
        CuratedCore("mednafen_ngp_libretro_android.so",     "Beetle NeoPop (NGP)",      listOf("ngp", "ngpc")),
        CuratedCore("mednafen_wswan_libretro_android.so",   "Beetle Cygne (WonderSwan)", listOf("wonderswan", "wonderswancolor", "ws", "wsc")),
        CuratedCore("stella_libretro_android.so",           "Stella (Atari 2600)",      listOf("atari2600")),
        CuratedCore("a5200_libretro_android.so",            "a5200 (Atari 5200)",       listOf("atari5200")),
        CuratedCore("prosystem_libretro_android.so",        "ProSystem (Atari 7800)",   listOf("atari7800")),
        CuratedCore("handy_libretro_android.so",            "Handy (Lynx)",             listOf("atarilynx", "lynx")),
        CuratedCore("mednafen_vb_libretro_android.so",      "Beetle VB (Virtual Boy)",  listOf("virtualboy", "vb")),
        CuratedCore("vice_x64_libretro_android.so",         "VICE x64 (C64)",           listOf("c64")),
    )

    // Core filename prefix (strip _libretro_android.so) → platform IDs
    private val CORE_PLATFORM_MAP: Map<String, List<String>> = mapOf(
        "nestopia"                    to listOf("nes", "fam"),
        "mesen"                       to listOf("nes", "fam"),
        "fceumm"                      to listOf("nes", "fam"),
        "snes9x"                      to listOf("snes"),
        "bsnes"                       to listOf("snes"),
        "bsnes_mercury_accuracy"      to listOf("snes"),
        "bsnes_mercury_balanced"      to listOf("snes"),
        "mesen-s"                     to listOf("snes"),
        "genesis_plus_gx"             to listOf("genesis", "megadrive", "mastersystem", "sms", "gamegear", "segacd"),
        "genesis_plus_gx_wide"        to listOf("genesis", "megadrive"),
        "picodrive"                   to listOf("genesis", "megadrive", "sms", "gamegear", "sega32x"),
        "gambatte"                    to listOf("gb", "gbc"),
        "mgba"                        to listOf("gb", "gbc", "gba"),
        "vba_next"                    to listOf("gba"),
        "vbam"                        to listOf("gba", "gb", "gbc"),
        "mupen64plus_next"            to listOf("n64"),
        "mupen64plus_next_gles3"      to listOf("n64"),
        "mupen64plus_next_gles2"      to listOf("n64"),
        "parallel_n64"                to listOf("n64"),
        "swanstation"                 to listOf("psx", "ps1"),
        "pcsx_rearmed"                to listOf("psx", "ps1"),
        "mednafen_psx"                to listOf("psx", "ps1"),
        "mednafen_psx_hw"             to listOf("psx", "ps1"),
        "pcsx2"                       to listOf("ps2"),
        "ppsspp"                      to listOf("psp"),
        "kronos"                      to listOf("saturn"),
        "desmume"                     to listOf("nds", "ds"),
        "melonds"                     to listOf("nds", "ds"),
        "dolphin"                     to listOf("gc", "gamecube", "wii"),
        "citra"                       to listOf("3ds", "n3ds"),
        "mame2003_plus"               to listOf("arcade", "cps1", "cps2"),
        "mame2003"                    to listOf("arcade", "cps1", "cps2"),
        "mame"                        to listOf("arcade", "cps1", "cps2", "cps3"),
        "fbneo"                       to listOf("arcade", "neogeo", "cps1", "cps2", "cps3"),
        "mednafen_pce"                to listOf("pce", "pcengine", "tgfx16"),
        "mednafen_pce_fast"           to listOf("pce", "pcengine", "tgfx16"),
        "mednafen_saturn"             to listOf("saturn"),
        "yabause"                     to listOf("saturn"),
        "yabasanshiro"                to listOf("saturn"),
        "stella"                      to listOf("atari2600"),
        "a5200"                       to listOf("atari5200"),
        "prosystem"                   to listOf("atari7800"),
        "smsplus"                     to listOf("sms", "mastersystem", "gamegear"),
        "bluemsx"                     to listOf("msx"),
        "fmsx"                        to listOf("msx"),
        "puae"                        to listOf("amiga"),
        "vice_x64"                    to listOf("c64"),
        "vice_x128"                   to listOf("c64"),
        "mednafen_lynx"               to listOf("lynx", "atarilynx"),
        "handy"                       to listOf("lynx", "atarilynx"),
        "mednafen_vb"                 to listOf("vb", "virtualboy"),
        "mednafen_wswan"              to listOf("ws", "wsc", "wonderswan", "wonderswancolor"),
        "race"                        to listOf("ngp", "ngpc"),
        "mednafen_ngp"                to listOf("ngp", "ngpc"),
        "opera"                       to listOf("3do"),
        "flycast"                     to listOf("dreamcast", "dc", "naomi", "atomiswave"),
    )

    /**
     * Cores to offer for an installed RetroArch [packageName].
     *
     * RetroArch loads cores only from its private internal directory (Android blocks dlopen() from
     * shared storage), and no other app can enumerate that directory directly. Two modes:
     *
     *  - [installedCoreFiles] == null → **not linked**: PFP cannot verify what's installed, so it
     *    offers the [CURATED_CORES] defaults (one per system) unverified. A core the user hasn't
     *    downloaded fails silently inside RetroArch — the profile notes say which to install.
     *  - [installedCoreFiles] non-null → **linked** (via [RetroArchLink]'s SAF grant, the
     *    authoritative list): PFP offers exactly one profile per actually-installed, platform-mapped
     *    core. Systems with no installed core get no RetroArch profile, so the user is never dropped
     *    into a black screen for a missing core.
     *
     * [RetroArchCore.absolutePath] always points at RetroArch's internal core path — PFP only names
     * the core in the LIBRETRO extra; RetroArch opens it itself.
     */
    fun coresFor(packageName: String, installedCoreFiles: Set<String>? = null): List<RetroArchCore> {
        val internalDir = "/data/data/$packageName/cores"

        if (installedCoreFiles != null) {
            val cores = installedCoreFiles.mapNotNull { fileName ->
                val prefix = fileName
                    .removeSuffix("_libretro_android.so")
                    .removeSuffix("_libretro.so")
                val platforms = CORE_PLATFORM_MAP[prefix] ?: return@mapNotNull null
                RetroArchCore(
                    name         = CURATED_CORES.firstOrNull { it.fileName == fileName }?.name
                                   ?: prefix.replace('_', ' ').replaceFirstChar { it.uppercaseChar() },
                    fileName     = fileName,
                    absolutePath = "$internalDir/$fileName",
                    platformIds  = platforms,
                )
            }.sortedBy { it.name }
            Timber.i("RetroArch cores (linked, $packageName): ${cores.size} installed & mapped of ${installedCoreFiles.size} present")
            return cores
        }

        val curated = CURATED_CORES.map { c ->
            RetroArchCore(
                name         = c.name,
                fileName     = c.fileName,
                absolutePath = "$internalDir/${c.fileName}",
                platformIds  = c.platformIds,
            )
        }
        val curatedNames = curated.mapTo(mutableSetOf()) { it.fileName }
        val extra = legacyCoreFileNames()
            .asSequence()
            .filterNot { it in curatedNames }
            .mapNotNull { fileName ->
                val prefix = fileName
                    .removeSuffix("_libretro_android.so")
                    .removeSuffix("_libretro.so")
                val platforms = CORE_PLATFORM_MAP[prefix] ?: return@mapNotNull null
                RetroArchCore(
                    name         = prefix.replace('_', ' ').replaceFirstChar { it.uppercaseChar() },
                    fileName     = fileName,
                    absolutePath = "$internalDir/$fileName",
                    platformIds  = platforms,
                )
            }
            .toList()

        Timber.i("RetroArch cores (unlinked, $packageName): ${curated.size} curated + ${extra.size} discovered")
        return curated + extra
    }

    // Extra core filenames staged in the legacy shared-storage dirs, if any exist.
    private fun legacyCoreFileNames(): List<String> {
        val coresDir = CORE_DIRS.map(::File).firstOrNull { it.isDirectory } ?: return emptyList()
        return try {
            coresDir.listFiles()
                ?.filter { it.isFile && it.name.contains("_libretro") && it.extension == "so" }
                ?.map { it.name }
                ?: emptyList()
        } catch (e: Exception) {
            Timber.w(e, "RetroArch legacy core scan failed")
            emptyList()
        }
    }
}
