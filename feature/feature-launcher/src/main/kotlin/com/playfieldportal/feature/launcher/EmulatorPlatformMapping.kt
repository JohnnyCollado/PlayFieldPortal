package com.playfieldportal.feature.launcher

import com.playfieldportal.core.domain.model.EmulatorProfile

/**
 * Platform-id → emulator-facing alias expansion, shared by every consumer that decides whether an
 * [EmulatorProfile] can run a console or which RetroArch core it maps.
 *
 * The launch pipeline previously carried three private copies of this table (in
 * EmulatorProfileRepository, EmulatorIntentResolver and GameDetailViewModel) plus a set/list variant
 * of the same alias map, and they had to stay in sync by hand. B4 ("emulator and core assignment
 * clarity") made this a single home so the resolved-launch explanation and the launch path read the
 * same mapping forever.
 */
fun platformAliases(platformId: String): List<String> = when (platformId) {
    "psx"          -> listOf("psx", "ps1")
    "ps1"          -> listOf("ps1", "psx")
    "n3ds"         -> listOf("n3ds", "3ds")
    "3ds"          -> listOf("3ds", "n3ds")
    "gc"           -> listOf("gc", "gamecube")
    "gamecube"     -> listOf("gamecube", "gc")
    "nds"          -> listOf("nds", "ds")
    "ds"           -> listOf("ds", "nds")
    "pcengine"     -> listOf("pcengine", "pce", "tgfx16")
    "pce"          -> listOf("pce", "pcengine", "tgfx16")
    "tgfx16"       -> listOf("tgfx16", "pce", "pcengine")
    "mastersystem" -> listOf("mastersystem", "sms")
    "sms"          -> listOf("sms", "mastersystem")
    "genesis"      -> listOf("genesis", "megadrive", "md")
    "megadrive"    -> listOf("megadrive", "genesis", "md")
    "md"           -> listOf("md", "genesis", "megadrive")
    "dreamcast"    -> listOf("dreamcast", "dc")
    "dc"           -> listOf("dc", "dreamcast")
    "virtualboy"   -> listOf("virtualboy", "vb")
    "vb"           -> listOf("vb", "virtualboy")
    "atarilynx"    -> listOf("atarilynx", "lynx")
    "lynx"         -> listOf("lynx", "atarilynx")
    "wonderswan"   -> listOf("wonderswan", "ws")
    "ws"           -> listOf("ws", "wonderswan")
    "wonderswancolor" -> listOf("wonderswancolor", "wsc")
    "wsc"          -> listOf("wsc", "wonderswancolor")
    "ngp"          -> listOf("ngp", "ngpc")
    "ngpc"         -> listOf("ngpc", "ngp")
    else           -> listOf(platformId)
}

/** True when [this] profile can launch games of [platformId] (canonical id or any alias). */
fun EmulatorProfile.supportsPlatform(platformId: String): Boolean {
    val aliases = platformAliases(platformId)
    return supportedPlatformIds.any { it in aliases }
}

/**
 * The RetroArch core path this profile maps for [platformId], or null when none is mapped.
 *
 * Core entries are stored per platform alias (a profile can hold "ps1" while the game's canonical
 * id is "psx"), so the map is probed through the alias expansion of [platformId]. The returned path
 * is normalized to this profile's own RetroArch package — see [normalizeRetroArchCorePath].
 */
fun EmulatorProfile.corePathFor(platformId: String): String? {
    for (alias in platformAliases(platformId)) {
        coreMap[alias]?.let { return normalizeRetroArchCorePath(it) }
    }
    return null
}

/**
 * Rewrites hard-coded canonical RetroArch core directories to the path under [this] profile's own
 * package. Core entries can be generated from one RetroArch build (e.g. `com.retroarch.aarch64`)
 * and later run under another package, so the path handed to RetroArch — and shown to the user —
 * must point at the package that will actually load it. Non-RetroArch profiles pass through
 * untouched (their coreMap values are already package-relative or informational).
 */
fun EmulatorProfile.normalizeRetroArchCorePath(corePath: String): String {
    if (!packageName.startsWith("com.retroarch")) return corePath
    return corePath
        .replace("/data/data/com.retroarch.aarch64/cores/", "/data/data/$packageName/cores/")
        .replace("/data/data/com.retroarch.ra64/cores/", "/data/data/$packageName/cores/")
        .replace("/data/data/com.retroarch.ra32/cores/", "/data/data/$packageName/cores/")
        .replace("/data/data/com.retroarch/cores/", "/data/data/$packageName/cores/")
}
