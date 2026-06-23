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
    val mimeType: String? = null,
    val useSafUri: Boolean = false,
)

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
            intentExtras     = mapOf("bootPath" to "{rom_path}"),
            intentBoolExtras = mapOf("resumeState" to false),
            intentFlags      = listOf("CLEAR_TASK", "CLEAR_TOP"),
        ),

        // ── PS2 ──────────────────────────────────────────────────────────────
        KnownEmulator(
            packageNames  = listOf("xyz.aethersx2.android"),
            suggestedName = "NetherSX2 / AetherSX2",
            platformIds   = listOf("ps2"),
            intentType    = IntentType.COMPONENT,
            activityClass = "xyz.aethersx2.android.EmulationActivity",
            intentExtras  = mapOf("bootPath" to "{rom_path}"),
            intentFlags   = listOf("CLEAR_TASK", "CLEAR_TOP"),
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
            packageNames  = listOf("com.dsemu.drastic"),
            suggestedName = "DraStic",
            platformIds   = listOf("nds", "ds"),
            activityClass = "com.dsemu.drastic.DraSticActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),

        // ── GameCube / Wii ────────────────────────────────────────────────────
        KnownEmulator(
            packageNames    = listOf("org.dolphinemu.dolphinemu"),
            suggestedName   = "Dolphin",
            platformIds     = listOf("gc", "gamecube", "wii"),
            intentType      = IntentType.COMPONENT,
            activityClass   = "org.dolphinemu.dolphinemu.ui.main.TvMainActivity",
            intentExtras    = mapOf("AutoStartFile" to "{rom_path}"),
            intentCategory  = "android.intent.category.LEANBACK_LAUNCHER",
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
            packageNames  = listOf("org.citra_emu.citra"),
            suggestedName = "Citra (3DS)",
            platformIds   = listOf("3ds", "n3ds"),
            activityClass = "org.citra_emu.citra.activities.EmulationActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),
        KnownEmulator(
            packageNames  = listOf("io.github.lime3ds.android"),
            suggestedName = "Lime3DS",
            platformIds   = listOf("3ds", "n3ds"),
            activityClass = "io.github.lime3ds.android.activities.EmulationActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),

        // ── Switch ────────────────────────────────────────────────────────────
        KnownEmulator(
            packageNames  = listOf("org.sudachi.android", "org.sudachi.sudachi_emu",
                                   "org.yuzu.yuzu_emu", "org.suyu.suyu_emu"),
            suggestedName = "Switch Emulator",
            platformIds   = listOf("switch", "nx"),
            mimeType      = "application/octet-stream",
            useSafUri     = true,
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

        // ── SNES ──────────────────────────────────────────────────────────────
        KnownEmulator(
            packageNames  = listOf("com.explusalpha.Snes9xPlus"),
            suggestedName = "Snes9x EX+",
            platformIds   = listOf("snes"),
            activityClass = "com.imagine.BaseActivity",
            mimeType      = "application/octet-stream",
            useSafUri     = true,
        ),

        // ── Genesis / Mega Drive ──────────────────────────────────────────────
        KnownEmulator(
            packageNames  = listOf("com.explusalpha.MdEmu"),
            suggestedName = "MD.emu (Genesis)",
            platformIds   = listOf("genesis", "megadrive", "md", "sms", "mastersystem"),
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
            mimeType      = "application/octet-stream",
        ),
    )
}
