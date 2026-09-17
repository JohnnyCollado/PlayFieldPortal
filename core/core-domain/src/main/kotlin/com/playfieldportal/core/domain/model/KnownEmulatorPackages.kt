package com.playfieldportal.core.domain.model

/**
 * Package names the App Drawer tags as emulators (the EMU badge and the Emulators filter).
 *
 * Lives in core-domain so the drawer can use it without depending on feature-launcher.
 * KnownEmulatorCatalogTest fails if a curated launch recipe's package is missing here, so the
 * two stay in step. The extra packages are emulators PFP recognizes but has no ROM-launch
 * recipe for: RetroArch, RPCSX, ScummVM, and the PC runtimes.
 *
 * Streaming clients (Moonlight, Artemis) and frontends are deliberately absent. GameHub-family
 * spoof packages (AnTuTu, PUBG, ...) are too: they are the genuine names of real apps, so only
 * the launcher-owned names are listed.
 */
object KnownEmulatorPackages {

    // Matched as the package itself or any `<family>.` variant (com.retroarch.aarch64, ...).
    private val families = setOf(
        "com.retroarch",
        "com.winlator",
        "xyz.aethersx2",
        "org.ppsspp",
    )

    private val packages = setOf(
        // Sony
        "org.vita3k.emulator", "org.vita3k.emulator.ikhoeyZX", "com.sbro.emucorev",
        "com.github.stenzek.duckstation", "com.epsxe.ePSXe", "com.emulator.fpse",
        "com.emulator.fpse64", "com.nanodata.armsx",
        "com.armsx2", "come.nanodata.armsx2", "come.nanodata.armsx2.debug",
        "com.virtualapplications.play", "com.sbro.emucorex",
        "aenu.aps3e", "aenu.aps3e.premium", "com.armsx3", "net.rpcsx",
        // Nintendo
        "me.magnum.melonds", "me.magnum.melonds.nightly", "me.magnum.melondualds",
        "com.dsemu.drastic", "com.hydra.noods", "com.sky.SkyEmu",
        "org.dolphinemu.dolphinemu", "org.mm.jr", "org.dolphinemu.mmjr",
        "org.dolphinemu.primehack", "org.shiiion.primehack",
        "info.cemu.cemu", "info.cemu.Cemu",
        "org.azahar_emu.azahar", "io.github.azaharplus.android", "org.citra.citra_emu",
        "org.citra.citra_emu.canary", "org.citra.emu", "io.github.lime3ds.android",
        "io.github.mandarine3ds.mandarine", "io.github.borked3ds.android", "com.panda3ds.pandroid",
        "dev.eden.eden_emulator", "dev.eden.eden_emulator.nightly", "dev.legacy.eden_emulator",
        "org.yuzu.yuzu_emu", "org.yuzu.yuzu_emu.ea", "org.sudachi.sudachi_emu",
        "org.sudachi.sudachi_emu.ea", "org.citron.citron_emu", "org.citron.citron_emu.ea",
        "com.sumi.SumiEmulator", "org.uzuy.uzuy_emu", "org.uzuy.uzuy_emu.ea",
        "org.uzuy.uzuy_emu.mmjr", "org.suyu.suyu_emu", "org.kenjinx.android",
        "org.benjisc.android", "skyline.emu",
        "io.mgba", "com.mgba.mgba", "com.fastemulator.gba", "com.fastemulator.gbafree",
        "com.fastemulator.gbc", "it.dbtecno.pizzaboygbapro", "it.dbtecno.pizzaboygba",
        "it.dbtecno.pizzaboypro", "it.dbtecno.pizzaboy", "com.pixelrespawn.linkboy",
        "org.mupen64plusae.v3.fzurita.pro", "org.mupen64plusae.v3.fzurita",
        "org.mupen64plusae.v3.fzurita.amazon", "org.mupen64plusae.v3.alpha",
        "com.fms.ines.free", "com.simongellis.vvb",
        // Sega and other consoles
        "it.dbtecno.pizzaboyscpro", "it.dbtecno.pizzaboyscbasic", "com.fms.mg",
        "org.devmiyax.yabasanshioro2.pro", "org.devmiyax.yabasanshioro2",
        "com.flycast.emulator", "com.flycast.emulator.gles2", "io.recompiled.redream",
        "com.seleuco.mame4d2024", "com.seleuco.mame4droid",
        // explusalpha's *.emu line
        "com.explusalpha.GbaEmu", "com.explusalpha.GbcEmu", "com.explusalpha.NesEmu",
        "com.explusalpha.Snes9xPlus", "com.explusalpha.MdEmu", "com.explusalpha.SaturnEmu",
        "com.PceEmu", "com.explusalpha.NeoEmu", "com.explusalpha.NgpEmu",
        "com.explusalpha.SwanEmu", "com.explusalpha.LynxEmu", "com.explusalpha.A2600Emu",
        "com.explusalpha.C64Emu",
        // Microsoft
        "com.izzy2lost.x1box", "emu.x360.mobile", "aenu.ax360e", "aenu.ax360e.free",
        // Other systems
        "com.github.eka2l1", "org.scummvm.scummvm", "io.wip.pico8", "com.rfandango.haku_x",
        // PC runtimes (Winlator is a family above)
        "app.gamenative", "gamehub.lite", "banner.hub", "com.xiaoji.egggame",
        "org.force9.starboard",
    )

    fun isEmulator(packageName: String): Boolean =
        packageName in packages ||
            families.any { packageName == it || packageName.startsWith("$it.") }
}
