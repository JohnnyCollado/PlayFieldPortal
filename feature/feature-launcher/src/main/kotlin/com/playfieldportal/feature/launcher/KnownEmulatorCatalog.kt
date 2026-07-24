package com.playfieldportal.feature.launcher

import com.playfieldportal.core.domain.model.IntentType

internal data class KnownEmulator(
    val packageNames: List<String>,   // tried in order; first installed one wins
    val suggestedName: String,
    val platformIds: List<String>,
    val intentType: IntentType = IntentType.ACTION_VIEW,
    val activityClass: String? = null,
    val intentExtras: Map<String, String> = emptyMap(),
    val intentBoolExtras: Map<String, Boolean> = emptyMap(),
    val intentAction: String? = null,
    val intentFlags: List<String> = emptyList(),
    val intentCategory: String? = null,
    val attachRomData: Boolean = false,
    val mimeType: String? = null,
    val useSafUri: Boolean = false,
)

/**
 * Curated launch recipes for standalone emulators, verified against the ES-DE Android
 * launch database and community find rules — see docs/emulator-intent-catalog-research.md
 * for sources and per-entry provenance. Only platforms present in PlatformSeeder get
 * entries; verified recipes for unseeded systems (MSX, 3DO, Jaguar, J2ME, ...) live in
 * the research doc until their platforms exist.
 *
 * Not representable yet (see research doc, Section C): Vita3K and EmuCoreV (string-array
 * extra + title-ID launch), GameNative (int extra), ScummVM (sidecar game-id launch),
 * RPCSX (emulation activity not exported). Winlator/GameHub/GameNative are handled by
 * the PC launcher subsystem, not this catalog.
 */
internal object KnownEmulatorCatalog {
    val entries: List<KnownEmulator> = listOf(

        // ── PSP ──────────────────────────────────────────────────────────────
        KnownEmulator(
            packageNames  = listOf("org.ppsspp.ppssppgold", "org.ppsspp.ppsspp"),
            suggestedName = "PPSSPP",
            platformIds   = listOf("psp"),
            activityClass = "org.ppsspp.ppsspp.PpssppActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),

        // ── PS1 ──────────────────────────────────────────────────────────────
        KnownEmulator(
            packageNames     = listOf("com.github.stenzek.duckstation"),
            suggestedName    = "DuckStation",
            platformIds      = listOf("psx", "ps1"),
            intentType       = IntentType.COMPONENT,
            activityClass    = "com.github.stenzek.duckstation.EmulationActivity",
            intentExtras     = mapOf("bootPath" to "{rom_uri}"),
            intentBoolExtras = mapOf("resumeState" to false),
            intentFlags      = listOf("CLEAR_TASK", "CLEAR_TOP"),
        ),
        KnownEmulator(
            // ePSXe only accepts a raw filesystem path; it cannot read content:// URIs,
            // so SAF-managed libraries will not launch through it.
            packageNames  = listOf("com.epsxe.ePSXe"),
            suggestedName = "ePSXe",
            platformIds   = listOf("psx", "ps1"),
            intentType    = IntentType.COMPONENT,
            activityClass = "com.epsxe.ePSXe.ePSXe",
            intentExtras  = mapOf("com.epsxe.ePSXe.isoName" to "{rom_path}"),
        ),
        KnownEmulator(
            packageNames  = listOf("com.emulator.fpse"),
            suggestedName = "FPse",
            platformIds   = listOf("psx", "ps1"),
            activityClass = "com.emulator.fpse.Main",
            mimeType      = "application/octet-stream",
        ),
        KnownEmulator(
            packageNames  = listOf("com.emulator.fpse64"),
            suggestedName = "FPseNG",
            platformIds   = listOf("psx", "ps1"),
            activityClass = "com.emulator.fpse64.Main",
            mimeType      = "application/octet-stream",
        ),

        // ── PS2 ──────────────────────────────────────────────────────────────
        KnownEmulator(
            packageNames  = listOf("xyz.aethersx2.android"),
            suggestedName = "NetherSX2 / AetherSX2",
            platformIds   = listOf("ps2"),
            intentType    = IntentType.COMPONENT,
            activityClass = "xyz.aethersx2.android.EmulationActivity",
            intentExtras  = mapOf("bootPath" to "{rom_uri}"),
            intentFlags   = listOf("CLEAR_TASK", "CLEAR_TOP"),
        ),
        KnownEmulator(
            packageNames  = listOf("xyz.aethersx2.tturnip"),
            suggestedName = "NetherSX2-Turnip",
            platformIds   = listOf("ps2"),
            intentType    = IntentType.COMPONENT,
            activityClass = "xyz.aethersx2.android.EmulationActivity",
            intentExtras  = mapOf("bootPath" to "{rom_uri}"),
            intentFlags   = listOf("CLEAR_TASK", "CLEAR_TOP"),
        ),
        KnownEmulator(
            packageNames  = listOf("xyz.aethersx2.cturnip"),
            suggestedName = "NetherSX2-Turnip Classic",
            platformIds   = listOf("ps2"),
            intentType    = IntentType.COMPONENT,
            activityClass = "xyz.aethersx2.android.EmulationActivity",
            intentExtras  = mapOf("bootPath" to "{rom_uri}"),
            intentFlags   = listOf("CLEAR_TASK", "CLEAR_TOP"),
        ),
        KnownEmulator(
            // NetherSX2 fork with a rewritten frontend: no EmulationActivity/bootPath.
            // Boots via ACTION_VIEW + content:// URI into the exported MainActivity alias
            // (scheme-only intent filter — the resolver's no-MIME fallback applies).
            packageNames  = listOf("com.armsx2"),
            suggestedName = "ARMSX2",
            platformIds   = listOf("ps2"),
            activityClass = "com.armsx2.MainActivity",
            useSafUri     = true,
        ),
        KnownEmulator(
            packageNames  = listOf("come.nanodata.armsx2", "come.nanodata.armsx2.debug"),
            suggestedName = "ARMSX2 (NanoData)",
            platformIds   = listOf("ps2"),
            activityClass = "kr.co.iefriends.pcsx2.MainActivity",
            useSafUri     = true,
        ),
        KnownEmulator(
            packageNames  = listOf("com.virtualapplications.play"),
            suggestedName = "Play!",
            platformIds   = listOf("ps2"),
            activityClass = "com.virtualapplications.play.MainActivity",
            useSafUri     = true,
        ),
        KnownEmulator(
            packageNames  = listOf("com.sbro.emucorex"),
            suggestedName = "EmuCoreX",
            platformIds   = listOf("ps2"),
            activityClass = "com.sbro.emucorex.MainActivity",
            intentFlags   = listOf("CLEAR_TASK"),
            useSafUri     = true,
        ),

        // ── PS3 ──────────────────────────────────────────────────────────────
        KnownEmulator(
            packageNames  = listOf("aenu.aps3e.premium", "aenu.aps3e"),
            suggestedName = "aPS3e",
            platformIds   = listOf("ps3"),
            intentType    = IntentType.COMPONENT,
            activityClass = "aenu.aps3e.EmulatorActivity",
            intentAction  = "aenu.intent.action.APS3E",
            intentExtras  = mapOf("iso_uri" to "{rom_uri}"),
        ),

        // ── Nintendo DS ───────────────────────────────────────────────────────
        KnownEmulator(
            packageNames  = listOf("me.magnum.melonds"),
            suggestedName = "melonDS",
            platformIds   = listOf("nds", "ds"),
            intentType    = IntentType.COMPONENT,
            activityClass = "me.magnum.melonds.ui.emulator.EmulatorActivity",
            intentAction  = "me.magnum.melonds.LAUNCH_ROM",
            intentExtras  = mapOf("uri" to "{rom_uri}"),
        ),
        KnownEmulator(
            // The LAUNCH_ROM action is package-prefixed, so the nightly build needs its
            // own entry rather than an extra package on the stable one.
            packageNames  = listOf("me.magnum.melonds.nightly"),
            suggestedName = "melonDS Nightly",
            platformIds   = listOf("nds", "ds"),
            intentType    = IntentType.COMPONENT,
            activityClass = "me.magnum.melonds.ui.emulator.EmulatorActivity",
            intentAction  = "me.magnum.melonds.nightly.LAUNCH_ROM",
            intentExtras  = mapOf("uri" to "{rom_uri}"),
        ),
        KnownEmulator(
            packageNames  = listOf("me.magnum.melondualds"),
            suggestedName = "melonDualDS",
            platformIds   = listOf("nds", "ds"),
            intentType    = IntentType.COMPONENT,
            activityClass = "me.magnum.melonds.ui.emulator.EmulatorActivity",
            intentAction  = "me.magnum.melonds.dev.LAUNCH_ROM",
            intentExtras  = mapOf("uri" to "{rom_uri}"),
        ),
        KnownEmulator(
            packageNames  = listOf("com.dsemu.drastic"),
            suggestedName = "DraStic",
            platformIds   = listOf("nds", "ds"),
            activityClass = "com.dsemu.drastic.DraSticActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),
        KnownEmulator(
            // NooDS only accepts a raw filesystem path in its LaunchPath extra.
            packageNames  = listOf("com.hydra.noods"),
            suggestedName = "NooDS",
            platformIds   = listOf("nds", "ds", "gba"),
            intentType    = IntentType.COMPONENT,
            activityClass = "com.hydra.noods.FileBrowser",
            intentExtras  = mapOf("LaunchPath" to "{rom_path}"),
            intentFlags   = listOf("CLEAR_TASK", "CLEAR_TOP"),
        ),
        KnownEmulator(
            packageNames  = listOf("com.sky.SkyEmu"),
            suggestedName = "SkyEmu",
            platformIds   = listOf("nds", "ds", "gb", "gbc", "gba"),
            activityClass = "com.sky.SkyEmu.EnhancedNativeActivity",
            intentFlags   = listOf("CLEAR_TASK", "CLEAR_TOP"),
            mimeType      = "application/octet-stream",
        ),

        // ── GameCube / Wii ────────────────────────────────────────────────────
        KnownEmulator(
            packageNames    = listOf("org.dolphinemu.dolphinemu"),
            suggestedName   = "Dolphin",
            platformIds     = listOf("gc", "gamecube", "wii"),
            intentType      = IntentType.COMPONENT,
            activityClass   = "org.dolphinemu.dolphinemu.ui.main.TvMainActivity",
            intentExtras    = mapOf("AutoStartFile" to "{rom_uri}"),
            intentCategory  = "android.intent.category.LEANBACK_LAUNCHER",
        ),
        KnownEmulator(
            packageNames  = listOf("org.mm.jr"),
            suggestedName = "Dolphin MMJR",
            platformIds   = listOf("gc", "gamecube", "wii"),
            intentType    = IntentType.COMPONENT,
            activityClass = "org.dolphinemu.dolphinemu.ui.main.MainActivity",
            intentAction  = "android.intent.action.VIEW",
            intentExtras  = mapOf("AutoStartFile" to "{rom_uri}"),
        ),
        KnownEmulator(
            packageNames  = listOf("org.dolphinemu.mmjr"),
            suggestedName = "Dolphin MMJR2",
            platformIds   = listOf("gc", "gamecube", "wii"),
            intentType    = IntentType.COMPONENT,
            activityClass = "org.dolphinemu.dolphinemu.ui.main.MainActivity",
            intentAction  = "android.intent.action.VIEW",
            intentExtras  = mapOf("AutoStartFile" to "{rom_uri}"),
        ),
        KnownEmulator(
            packageNames  = listOf("org.dolphinemu.primehack", "org.shiiion.primehack"),
            suggestedName = "Dolphin PrimeHack",
            platformIds   = listOf("gc", "gamecube", "wii"),
            intentType    = IntentType.COMPONENT,
            activityClass = "org.dolphinemu.dolphinemu.ui.main.MainActivity",
            intentAction  = "android.intent.action.VIEW",
            intentExtras  = mapOf("AutoStartFile" to "{rom_uri}"),
        ),

        // ── Wii U ─────────────────────────────────────────────────────────────
        KnownEmulator(
            packageNames  = listOf("info.cemu.cemu"),
            suggestedName = "Cemu",
            platformIds   = listOf("wiiu"),
            activityClass = "info.cemu.cemu.emulation.EmulationActivity",
            useSafUri     = true,
        ),
        KnownEmulator(
            // Older builds shipped under a different-case package with a matching
            // activity FQCN, so they need a separate entry.
            packageNames  = listOf("info.cemu.Cemu"),
            suggestedName = "Cemu (legacy)",
            platformIds   = listOf("wiiu"),
            activityClass = "info.cemu.Cemu.emulation.EmulationActivity",
            useSafUri     = true,
        ),

        // ── 3DS ──────────────────────────────────────────────────────────────
        KnownEmulator(
            packageNames  = listOf("org.azahar_emu.azahar"),
            suggestedName = "Azahar (3DS)",
            platformIds   = listOf("3ds", "n3ds"),
            activityClass = "org.citra.citra_emu.activities.EmulationActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),
        KnownEmulator(
            packageNames  = listOf("io.github.azaharplus.android"),
            suggestedName = "AzaharPlus (3DS)",
            platformIds   = listOf("3ds", "n3ds"),
            activityClass = "org.citra.citra_emu.activities.EmulationActivity",
            intentFlags   = listOf("CLEAR_TASK", "CLEAR_TOP"),
            useSafUri     = true,
        ),
        KnownEmulator(
            packageNames  = listOf("org.citra.citra_emu", "org.citra.citra_emu.canary"),
            suggestedName = "Citra (3DS)",
            platformIds   = listOf("3ds", "n3ds"),
            activityClass = "org.citra.citra_emu.activities.EmulationActivity",
            intentFlags   = listOf("CLEAR_TASK", "CLEAR_TOP"),
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),
        KnownEmulator(
            // Citra MMJ launches through a GamePath extra with a raw filesystem path.
            packageNames  = listOf("org.citra.emu"),
            suggestedName = "Citra MMJ (3DS)",
            platformIds   = listOf("3ds", "n3ds"),
            intentType    = IntentType.COMPONENT,
            activityClass = "org.citra.emu.ui.EmulationActivity",
            intentExtras  = mapOf("GamePath" to "{rom_path}"),
        ),
        KnownEmulator(
            packageNames  = listOf("io.github.lime3ds.android"),
            suggestedName = "Lime3DS",
            platformIds   = listOf("3ds", "n3ds"),
            activityClass = "io.github.lime3ds.android.activities.EmulationActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),
        KnownEmulator(
            packageNames  = listOf("io.github.mandarine3ds.mandarine"),
            suggestedName = "Mandarine (3DS)",
            platformIds   = listOf("3ds", "n3ds"),
            activityClass = "io.github.mandarine3ds.mandarine.activities.EmulationActivity",
            intentFlags   = listOf("CLEAR_TASK", "CLEAR_TOP"),
            useSafUri     = true,
        ),
        KnownEmulator(
            packageNames  = listOf("io.github.borked3ds.android"),
            suggestedName = "Borked3DS",
            platformIds   = listOf("3ds", "n3ds"),
            activityClass = "io.github.borked3ds.android.activities.EmulationActivity",
            intentFlags   = listOf("CLEAR_TASK", "CLEAR_TOP"),
            useSafUri     = true,
        ),
        KnownEmulator(
            packageNames  = listOf("com.panda3ds.pandroid"),
            suggestedName = "Panda3DS",
            platformIds   = listOf("3ds", "n3ds"),
            activityClass = "com.panda3ds.pandroid.app.MainActivity",
        ),

        // ── Switch ────────────────────────────────────────────────────────────
        // The yuzu lineage's EmulationActivity only accepts action
        // android.nfc.action.TECH_DISCOVERED with the ROM as a content:// data URI
        // (attachRomData). Driver-spoof builds (com.miHoYo.*, com.antutu.*) are
        // deliberately excluded — package name alone would mislabel the real app.
        KnownEmulator(
            packageNames  = listOf("dev.eden.eden_emulator", "dev.eden.eden_emulator.nightly",
                                   "dev.legacy.eden_emulator"),
            suggestedName = "Eden (Switch)",
            platformIds   = listOf("switch", "nx"),
            intentType    = IntentType.COMPONENT,
            activityClass = "org.yuzu.yuzu_emu.activities.EmulationActivity",
            intentAction  = "android.nfc.action.TECH_DISCOVERED",
            attachRomData = true,
        ),
        KnownEmulator(
            packageNames  = listOf("org.yuzu.yuzu_emu", "org.yuzu.yuzu_emu.ea"),
            suggestedName = "Yuzu (Switch)",
            platformIds   = listOf("switch", "nx"),
            intentType    = IntentType.COMPONENT,
            activityClass = "org.yuzu.yuzu_emu.activities.EmulationActivity",
            intentAction  = "android.nfc.action.TECH_DISCOVERED",
            attachRomData = true,
        ),
        KnownEmulator(
            packageNames  = listOf("org.sudachi.sudachi_emu", "org.sudachi.sudachi_emu.ea"),
            suggestedName = "Sudachi (Switch)",
            platformIds   = listOf("switch", "nx"),
            intentType    = IntentType.COMPONENT,
            activityClass = "org.sudachi.sudachi_emu.activities.EmulationActivity",
            intentAction  = "android.nfc.action.TECH_DISCOVERED",
            attachRomData = true,
        ),
        KnownEmulator(
            packageNames  = listOf("org.citron.citron_emu", "org.citron.citron_emu.ea"),
            suggestedName = "Citron (Switch)",
            platformIds   = listOf("switch", "nx"),
            intentType    = IntentType.COMPONENT,
            activityClass = "org.citron.citron_emu.activities.EmulationActivity",
            intentAction  = "android.nfc.action.TECH_DISCOVERED",
            attachRomData = true,
        ),
        KnownEmulator(
            packageNames  = listOf("com.sumi.SumiEmulator"),
            suggestedName = "Sumi (Switch)",
            platformIds   = listOf("switch", "nx"),
            intentType    = IntentType.COMPONENT,
            activityClass = "org.sumi.sumi_emu.activities.EmulationActivity",
            intentAction  = "android.nfc.action.TECH_DISCOVERED",
            attachRomData = true,
        ),
        KnownEmulator(
            packageNames  = listOf("org.uzuy.uzuy_emu", "org.uzuy.uzuy_emu.ea",
                                   "org.uzuy.uzuy_emu.mmjr"),
            suggestedName = "Uzuy (Switch)",
            platformIds   = listOf("switch", "nx"),
            intentType    = IntentType.COMPONENT,
            activityClass = "org.uzuy.uzuy_emu.activities.EmulationActivity",
            intentAction  = "android.nfc.action.TECH_DISCOVERED",
            attachRomData = true,
        ),
        KnownEmulator(
            packageNames  = listOf("org.suyu.suyu_emu"),
            suggestedName = "Suyu (Switch)",
            platformIds   = listOf("switch", "nx"),
            activityClass = "org.suyu.suyu_emu.activities.EmulationActivity",
        ),
        KnownEmulator(
            packageNames  = listOf("org.kenjinx.android"),
            suggestedName = "Kenji-NX (Switch)",
            platformIds   = listOf("switch", "nx"),
            intentType    = IntentType.COMPONENT,
            activityClass = "org.kenjinx.android.MainActivity",
            intentAction  = "org.kenjinx.android.LAUNCH_GAME",
            intentExtras  = mapOf("bootPath" to "{rom_uri}"),
        ),
        KnownEmulator(
            packageNames  = listOf("org.benjisc.android"),
            suggestedName = "Benji-SC (Switch)",
            platformIds   = listOf("switch", "nx"),
            intentType    = IntentType.COMPONENT,
            activityClass = "org.kenjinx.android.MainActivity",
            intentAction  = "org.kenjinx.android.LAUNCH_GAME",
            intentExtras  = mapOf("bootPath" to "{rom_uri}"),
        ),
        KnownEmulator(
            packageNames  = listOf("skyline.emu"),
            suggestedName = "Skyline (Switch)",
            platformIds   = listOf("switch", "nx"),
            activityClass = "emu.skyline.EmulationActivity",
        ),

        // ── Game Boy / GBC / GBA ─────────────────────────────────────────────
        KnownEmulator(
            packageNames  = listOf("io.mgba", "com.mgba.mgba"),
            suggestedName = "mGBA",
            platformIds   = listOf("gb", "gbc", "gba"),
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),
        KnownEmulator(
            packageNames  = listOf("com.fastemulator.gba"),
            suggestedName = "My Boy! (GBA)",
            platformIds   = listOf("gba"),
            activityClass = "com.fastemulator.gba.EmulatorActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),
        KnownEmulator(
            packageNames  = listOf("com.fastemulator.gbafree"),
            suggestedName = "My Boy! Free (GBA)",
            platformIds   = listOf("gba"),
            activityClass = "com.fastemulator.gbafree.EmulatorActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),
        KnownEmulator(
            packageNames  = listOf("com.fastemulator.gbc"),
            suggestedName = "My OldBoy! (GBC)",
            platformIds   = listOf("gbc", "gb"),
            activityClass = "com.fastemulator.gbc.EmulatorActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),
        KnownEmulator(
            packageNames  = listOf("com.explusalpha.GbaEmu"),
            suggestedName = "GBA.emu",
            platformIds   = listOf("gba"),
            activityClass = "com.imagine.BaseActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),
        KnownEmulator(
            packageNames  = listOf("com.explusalpha.GbcEmu"),
            suggestedName = "GBC.emu",
            platformIds   = listOf("gbc", "gb"),
            activityClass = "com.imagine.BaseActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),
        // Pizza Boy launches via a rom_uri string extra; each package variant has its own
        // activity FQCN, so pro/free are separate entries.
        KnownEmulator(
            packageNames  = listOf("it.dbtecno.pizzaboygbapro"),
            suggestedName = "Pizza Boy GBA Pro",
            platformIds   = listOf("gba"),
            intentType    = IntentType.COMPONENT,
            activityClass = "it.dbtecno.pizzaboygbapro.MainActivity",
            intentExtras  = mapOf("rom_uri" to "{rom_uri}"),
            intentFlags   = listOf("CLEAR_TASK", "CLEAR_TOP"),
        ),
        KnownEmulator(
            packageNames  = listOf("it.dbtecno.pizzaboygba"),
            suggestedName = "Pizza Boy GBA",
            platformIds   = listOf("gba"),
            intentType    = IntentType.COMPONENT,
            activityClass = "it.dbtecno.pizzaboygba.MainActivity",
            intentExtras  = mapOf("rom_uri" to "{rom_uri}"),
            intentFlags   = listOf("CLEAR_TASK", "CLEAR_TOP"),
        ),
        KnownEmulator(
            packageNames  = listOf("it.dbtecno.pizzaboypro"),
            suggestedName = "Pizza Boy GBC Pro",
            platformIds   = listOf("gbc", "gb"),
            intentType    = IntentType.COMPONENT,
            activityClass = "it.dbtecno.pizzaboypro.MainActivity",
            intentExtras  = mapOf("rom_uri" to "{rom_uri}"),
            intentFlags   = listOf("CLEAR_TASK", "CLEAR_TOP"),
        ),
        KnownEmulator(
            packageNames  = listOf("it.dbtecno.pizzaboy"),
            suggestedName = "Pizza Boy GBC",
            platformIds   = listOf("gbc", "gb"),
            intentType    = IntentType.COMPONENT,
            activityClass = "it.dbtecno.pizzaboy.MainActivity",
            intentExtras  = mapOf("rom_uri" to "{rom_uri}"),
            intentFlags   = listOf("CLEAR_TASK", "CLEAR_TOP"),
        ),
        KnownEmulator(
            packageNames  = listOf("com.pixelrespawn.linkboy"),
            suggestedName = "Linkboy",
            platformIds   = listOf("gb", "gbc", "gba"),
            activityClass = "com.pixelrespawn.linkboy.EmulatorActivity",
            useSafUri     = true,
        ),

        // ── N64 ──────────────────────────────────────────────────────────────
        KnownEmulator(
            packageNames  = listOf("org.mupen64plusae.v3.fzurita.pro",
                                   "org.mupen64plusae.v3.fzurita",
                                   "org.mupen64plusae.v3.fzurita.amazon"),
            suggestedName = "M64Plus FZ",
            platformIds   = listOf("n64"),
            activityClass = "paulscode.android.mupen64plusae.SplashActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),
        KnownEmulator(
            packageNames  = listOf("org.mupen64plusae.v3.alpha"),
            suggestedName = "Mupen64Plus-AE",
            platformIds   = listOf("n64"),
            activityClass = "paulscode.android.mupen64plusae.SplashActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),

        // ── NES / Famicom ─────────────────────────────────────────────────────
        KnownEmulator(
            packageNames  = listOf("com.explusalpha.NesEmu"),
            suggestedName = "NES.emu",
            platformIds   = listOf("nes", "fam"),
            activityClass = "com.imagine.BaseActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),
        KnownEmulator(
            packageNames  = listOf("com.fms.ines.free"),
            suggestedName = "iNES",
            platformIds   = listOf("nes", "fam"),
            activityClass = "com.fms.emulib.TVActivity",
            intentFlags   = listOf("CLEAR_TASK", "CLEAR_TOP"),
            useSafUri     = true,
        ),

        // ── SNES ──────────────────────────────────────────────────────────────
        KnownEmulator(
            packageNames  = listOf("com.explusalpha.Snes9xPlus"),
            suggestedName = "Snes9x EX+",
            platformIds   = listOf("snes"),
            activityClass = "com.imagine.BaseActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),

        // ── Virtual Boy ───────────────────────────────────────────────────────
        KnownEmulator(
            packageNames  = listOf("com.simongellis.vvb"),
            suggestedName = "Virtual Virtual Boy",
            platformIds   = listOf("virtualboy", "vb"),
            activityClass = "com.simongellis.vvb.MainActivity",
            intentFlags   = listOf("CLEAR_TASK", "CLEAR_TOP"),
        ),

        // ── Genesis / Mega Drive / Master System / Game Gear ──────────────────
        KnownEmulator(
            packageNames  = listOf("com.explusalpha.MdEmu"),
            suggestedName = "MD.emu (Genesis)",
            platformIds   = listOf("genesis", "megadrive", "md", "sms", "mastersystem"),
            activityClass = "com.imagine.BaseActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),
        KnownEmulator(
            packageNames  = listOf("it.dbtecno.pizzaboyscpro"),
            suggestedName = "Pizza Boy SC Pro",
            platformIds   = listOf("megadrive", "genesis", "md", "mastersystem", "sms", "gamegear"),
            intentType    = IntentType.COMPONENT,
            activityClass = "it.dbtecno.pizzaboyscpro.MainActivity",
            intentExtras  = mapOf("rom_uri" to "{rom_uri}"),
            intentFlags   = listOf("CLEAR_TASK", "CLEAR_TOP"),
        ),
        KnownEmulator(
            packageNames  = listOf("it.dbtecno.pizzaboyscbasic"),
            suggestedName = "Pizza Boy SC",
            platformIds   = listOf("megadrive", "genesis", "md", "mastersystem", "sms", "gamegear"),
            intentType    = IntentType.COMPONENT,
            activityClass = "it.dbtecno.pizzaboyscbasic.MainActivity",
            intentExtras  = mapOf("rom_uri" to "{rom_uri}"),
            intentFlags   = listOf("CLEAR_TASK", "CLEAR_TOP"),
        ),
        KnownEmulator(
            packageNames  = listOf("com.fms.mg"),
            suggestedName = "MasterGear",
            platformIds   = listOf("mastersystem", "sms", "gamegear"),
            activityClass = "com.fms.emulib.TVActivity",
            intentFlags   = listOf("CLEAR_TASK", "CLEAR_TOP"),
            useSafUri     = true,
        ),

        // ── Saturn ────────────────────────────────────────────────────────────
        KnownEmulator(
            packageNames  = listOf("org.devmiyax.yabasanshioro2.pro", "org.devmiyax.yabasanshioro2"),
            suggestedName = "Yaba Sanshiro 2 (Saturn)",
            platformIds   = listOf("saturn"),
            intentType    = IntentType.COMPONENT,
            activityClass = "org.uoyabause.android.Yabause",
            intentAction  = "android.intent.action.VIEW",
            intentExtras  = mapOf("org.uoyabause.android.FileNameUri" to "{rom_uri}"),
            intentFlags   = listOf("CLEAR_TASK", "CLEAR_TOP"),
        ),
        KnownEmulator(
            packageNames  = listOf("com.explusalpha.SaturnEmu"),
            suggestedName = "Saturn.emu",
            platformIds   = listOf("saturn"),
            activityClass = "com.imagine.BaseActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),

        // ── PC Engine ─────────────────────────────────────────────────────────
        KnownEmulator(
            packageNames  = listOf("com.PceEmu"),
            suggestedName = "PCE.emu",
            platformIds   = listOf("pcengine", "pce", "tgfx16"),
            activityClass = "com.imagine.BaseActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),

        // ── Neo Geo ───────────────────────────────────────────────────────────
        KnownEmulator(
            packageNames  = listOf("com.explusalpha.NeoEmu"),
            suggestedName = "NEO.emu",
            platformIds   = listOf("neogeo", "arcade"),
            activityClass = "com.imagine.BaseActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),

        // ── Neo Geo Pocket ────────────────────────────────────────────────────
        KnownEmulator(
            packageNames  = listOf("com.explusalpha.NgpEmu"),
            suggestedName = "NGP.emu",
            platformIds   = listOf("ngp", "ngpc"),
            activityClass = "com.imagine.BaseActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),

        // ── Arcade / MAME ─────────────────────────────────────────────────────
        KnownEmulator(
            packageNames  = listOf("com.seleuco.mame4d2024"),
            suggestedName = "MAME4droid 2024",
            platformIds   = listOf("mame", "arcade"),
            activityClass = "com.seleuco.mame4droid.MAME4droid",
            useSafUri     = true,
        ),
        KnownEmulator(
            packageNames  = listOf("com.seleuco.mame4droid"),
            suggestedName = "MAME4droid 0.139",
            platformIds   = listOf("mame", "arcade"),
            activityClass = "com.seleuco.mame4droid.MAME4droid",
            useSafUri     = true,
        ),

        // ── WonderSwan ────────────────────────────────────────────────────────
        KnownEmulator(
            packageNames  = listOf("com.explusalpha.SwanEmu"),
            suggestedName = "Swan.emu",
            platformIds   = listOf("wonderswan", "wonderswancolor", "ws", "wsc"),
            activityClass = "com.imagine.BaseActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),

        // ── Atari Lynx ────────────────────────────────────────────────────────
        KnownEmulator(
            packageNames  = listOf("com.explusalpha.LynxEmu"),
            suggestedName = "Lynx.emu",
            platformIds   = listOf("atarilynx", "lynx"),
            activityClass = "com.imagine.BaseActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),

        // ── Atari 2600 ────────────────────────────────────────────────────────
        KnownEmulator(
            packageNames  = listOf("com.explusalpha.A2600Emu"),
            suggestedName = "2600.emu",
            platformIds   = listOf("atari2600"),
            activityClass = "com.imagine.BaseActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),

        // ── Commodore 64 ──────────────────────────────────────────────────────
        KnownEmulator(
            packageNames  = listOf("com.explusalpha.C64Emu"),
            suggestedName = "C64.emu",
            platformIds   = listOf("c64"),
            activityClass = "com.imagine.BaseActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),

        // ── Dreamcast ─────────────────────────────────────────────────────────
        KnownEmulator(
            packageNames  = listOf("com.flycast.emulator", "com.flycast.emulator.gles2"),
            suggestedName = "Flycast (Dreamcast)",
            platformIds   = listOf("dreamcast", "dc", "naomi", "atomiswave"),
            activityClass = "com.flycast.emulator.MainActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),
        KnownEmulator(
            packageNames  = listOf("io.recompiled.redream"),
            suggestedName = "Redream (Dreamcast)",
            platformIds   = listOf("dreamcast", "dc"),
            activityClass = "io.recompiled.redream.MainActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),

        // ── Symbian ───────────────────────────────────────────────────────────
        KnownEmulator(
            packageNames  = listOf("com.github.eka2l1"),
            suggestedName = "EKA2L1 (Symbian)",
            platformIds   = listOf("symbian"),
            activityClass = "com.github.eka2l1.emu.EmulatorActivity",
            intentFlags   = listOf("CLEAR_TASK", "CLEAR_TOP"),
            mimeType      = "application/octet-stream",
        ),

        // ── Xbox 360 ──────────────────────────────────────────────────────────
        // X360 Mobile exposes X360MobileGameLaunchActivity with an ACTION_VIEW filter
        // (file/content scheme, */* type, pathPattern *.iso/.xex/.zar/.xbla).
        KnownEmulator(
            packageNames  = listOf("emu.x360.mobile"),
            suggestedName = "X360 Mobile",
            platformIds   = listOf("x360", "xbox360"),
            activityClass = "emu.x360.mobile.X360MobileGameLaunchActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),
        KnownEmulator(
            packageNames  = listOf("aenu.ax360e", "aenu.ax360e.free"),
            suggestedName = "aX360e",
            platformIds   = listOf("x360", "xbox360"),
            intentType    = IntentType.COMPONENT,
            activityClass = "aenu.ax360e.EmulatorActivity",
            intentAction  = "aenu.intent.action.AX360E",
            intentExtras  = mapOf("game_uri" to "{rom_uri}"),
        ),
    )
}
