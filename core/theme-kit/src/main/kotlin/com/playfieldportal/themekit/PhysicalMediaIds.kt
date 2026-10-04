package com.playfieldportal.themekit

/**
 * A platform id's physical media, in one table: the bundled art file that draws it (under the
 * launcher's `assets/systems/physical-media/`, copied into the Theme Studio) and the
 * `physmedia_<id>` slot a user pick or a theme replaces it through.
 *
 * The two differ on purpose: art can be regional (a Genesis game shows the Genesis cart, a
 * TurboGrafx-16 game the TG-16 HuCard) while the slot is the console's (Genesis and Mega Drive
 * share one). Both the launcher and the Studio read this table, so they can never disagree.
 */
object PhysicalMediaIds {

    /** Where an alias's art and slot come from. */
    data class Alias(val art: String, val slotId: String)

    /** Platform ids that are not their own art file, their own slot id, or both. */
    val ALIASES: Map<String, Alias> = mapOf(
        "ps1" to Alias("psx", "psx"),
        "fam" to Alias("nes", "nes"),
        "famicom" to Alias("nes", "nes"),
        "sfc" to Alias("sfc", "snes"),
        "ds" to Alias("nds", "nds"),
        "3ds" to Alias("n3ds", "n3ds"),
        "nx" to Alias("switch", "switch"),
        "gamecube" to Alias("gc", "gc"),
        "md" to Alias("megadrive", "megadrive"),
        "genesis" to Alias("genesis", "megadrive"),
        "sms" to Alias("mastersystem", "mastersystem"),
        "dc" to Alias("dreamcast", "dreamcast"),
        "arcade" to Alias("arcade", "mame"),
        "naomi" to Alias("arcade", "mame"),
        "atomiswave" to Alias("arcade", "mame"),
        "pce" to Alias("pcengine", "pcengine"),
        "tgfx16" to Alias("tg16", "pcengine"),
        "lynx" to Alias("atarilynx", "atarilynx"),
        "vb" to Alias("virtualboy", "virtualboy"),
        "ws" to Alias("wonderswan", "wonderswan"),
        "wsc" to Alias("wonderswancolor", "wonderswancolor"),
        "ngpc" to Alias("ngpc", "ngp"),
        // The console id is x360; its art file has always been xbox360.png.
        "x360" to Alias("xbox360", "x360"),
        "xbox360" to Alias("xbox360", "x360"),
    )

    /**
     * Digital-only: no physical media at all. "windows" is NOT here — PC games ship on discs, so
     * the card gets windows.png. Storefront platforms stay digital-only.
     */
    val DIGITAL_ONLY: Set<String> = setOf("android", "steam", "gog", "default")

    /** The art file (without `.png`) for [platformId], or null for a digital-only or absent platform. */
    fun artFile(platformId: String?): String? {
        val id = platformId?.takeUnless { it in DIGITAL_ONLY } ?: return null
        return ALIASES[id]?.art ?: id
    }

    /** The `physmedia_` slot key that themes [platformId]'s media art, or null when no slot does. */
    fun slotKey(platformId: String?): String? {
        val id = platformId?.takeUnless { it in DIGITAL_ONLY } ?: return null
        val key = CustomizableIcons.PHYSICAL_MEDIA_PREFIX + (ALIASES[id]?.slotId ?: id)
        return key.takeIf(CustomizableIcons::isValidKey)
    }
}
