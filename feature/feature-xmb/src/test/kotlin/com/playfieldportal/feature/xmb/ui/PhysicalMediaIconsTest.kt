package com.playfieldportal.feature.xmb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PhysicalMediaIconsTest {

    // ── physicalMediaAssetName: aliases map to the correct PNG filename ─────────

    @Test fun `ps1 alias resolves to psx`() = assertEquals("psx", physicalMediaAssetName("ps1"))
    @Test fun `psx is unchanged`() = assertEquals("psx", physicalMediaAssetName("psx"))
    @Test fun `fam alias resolves to nes`() = assertEquals("nes", physicalMediaAssetName("fam"))
    @Test fun `nes is unchanged`() = assertEquals("nes", physicalMediaAssetName("nes"))
    @Test fun `sfc is unchanged`() = assertEquals("sfc", physicalMediaAssetName("sfc"))
    @Test fun `snes is unchanged`() = assertEquals("snes", physicalMediaAssetName("snes"))
    @Test fun `ds alias resolves to nds`() = assertEquals("nds", physicalMediaAssetName("ds"))
    @Test fun `nds is unchanged`() = assertEquals("nds", physicalMediaAssetName("nds"))
    @Test fun `3ds alias resolves to n3ds`() = assertEquals("n3ds", physicalMediaAssetName("3ds"))
    @Test fun `n3ds is unchanged`() = assertEquals("n3ds", physicalMediaAssetName("n3ds"))
    @Test fun `nx alias resolves to switch`() = assertEquals("switch", physicalMediaAssetName("nx"))
    @Test fun `switch is unchanged`() = assertEquals("switch", physicalMediaAssetName("switch"))
    @Test fun `gamecube alias resolves to gc`() = assertEquals("gc", physicalMediaAssetName("gamecube"))
    @Test fun `gc is unchanged`() = assertEquals("gc", physicalMediaAssetName("gc"))
    @Test fun `md alias resolves to megadrive`() = assertEquals("megadrive", physicalMediaAssetName("md"))
    @Test fun `megadrive is unchanged`() = assertEquals("megadrive", physicalMediaAssetName("megadrive"))
    @Test fun `genesis is unchanged`() = assertEquals("genesis", physicalMediaAssetName("genesis"))
    @Test fun `sms alias resolves to mastersystem`() = assertEquals("mastersystem", physicalMediaAssetName("sms"))
    @Test fun `mastersystem is unchanged`() = assertEquals("mastersystem", physicalMediaAssetName("mastersystem"))
    @Test fun `dc alias resolves to dreamcast`() = assertEquals("dreamcast", physicalMediaAssetName("dc"))
    @Test fun `dreamcast is unchanged`() = assertEquals("dreamcast", physicalMediaAssetName("dreamcast"))
    @Test fun `naomi alias resolves to arcade`() = assertEquals("arcade", physicalMediaAssetName("naomi"))
    @Test fun `atomiswave alias resolves to arcade`() = assertEquals("arcade", physicalMediaAssetName("atomiswave"))
    @Test fun `pce alias resolves to pcengine`() = assertEquals("pcengine", physicalMediaAssetName("pce"))
    @Test fun `tgfx16 alias resolves to tg16`() = assertEquals("tg16", physicalMediaAssetName("tgfx16"))
    @Test fun `pcengine is unchanged`() = assertEquals("pcengine", physicalMediaAssetName("pcengine"))
    @Test fun `lynx alias resolves to atarilynx`() = assertEquals("atarilynx", physicalMediaAssetName("lynx"))
    @Test fun `atarilynx is unchanged`() = assertEquals("atarilynx", physicalMediaAssetName("atarilynx"))
    @Test fun `vb alias resolves to virtualboy`() = assertEquals("virtualboy", physicalMediaAssetName("vb"))
    @Test fun `virtualboy is unchanged`() = assertEquals("virtualboy", physicalMediaAssetName("virtualboy"))
    @Test fun `ws alias resolves to wonderswan`() = assertEquals("wonderswan", physicalMediaAssetName("ws"))
    @Test fun `wsc alias resolves to wonderswancolor`() = assertEquals("wonderswancolor", physicalMediaAssetName("wsc"))
    @Test fun `wonderswan is unchanged`() = assertEquals("wonderswan", physicalMediaAssetName("wonderswan"))
    @Test fun `wonderswancolor is unchanged`() = assertEquals("wonderswancolor", physicalMediaAssetName("wonderswancolor"))

    // Direct-match platforms (platformId == filename)
    @Test fun `psp is unchanged`() = assertEquals("psp", physicalMediaAssetName("psp"))
    @Test fun `ps2 is unchanged`() = assertEquals("ps2", physicalMediaAssetName("ps2"))
    @Test fun `saturn is unchanged`() = assertEquals("saturn", physicalMediaAssetName("saturn"))
    @Test fun `n64 is unchanged`() = assertEquals("n64", physicalMediaAssetName("n64"))
    @Test fun `gb is unchanged`() = assertEquals("gb", physicalMediaAssetName("gb"))
    @Test fun `gbc is unchanged`() = assertEquals("gbc", physicalMediaAssetName("gbc"))
    @Test fun `gba is unchanged`() = assertEquals("gba", physicalMediaAssetName("gba"))
    @Test fun `ngp is unchanged`() = assertEquals("ngp", physicalMediaAssetName("ngp"))
    @Test fun `ngpc is unchanged`() = assertEquals("ngpc", physicalMediaAssetName("ngpc"))

    // Digital-only — must return null
    @Test fun `android returns null`() = assertNull(physicalMediaAssetName("android"))
    @Test fun `windows returns null`() = assertNull(physicalMediaAssetName("windows"))
    @Test fun `null returns null`() = assertNull(physicalMediaAssetName(null))

    // ── physicalMediaIconRes: every platform alias has a vector fallback ────────

    @Test fun `psx fallback exists`() = assertNotNull(physicalMediaIconRes("psx"))
    @Test fun `ps1 fallback exists`() = assertNotNull(physicalMediaIconRes("ps1"))
    @Test fun `psp fallback exists`() = assertNotNull(physicalMediaIconRes("psp"))
    @Test fun `dc fallback exists`() = assertNotNull(physicalMediaIconRes("dc"))
    @Test fun `gamecube fallback exists`() = assertNotNull(physicalMediaIconRes("gamecube"))
    @Test fun `md fallback exists`() = assertNotNull(physicalMediaIconRes("md"))
    @Test fun `sms fallback exists`() = assertNotNull(physicalMediaIconRes("sms"))
    @Test fun `ds fallback exists`() = assertNotNull(physicalMediaIconRes("ds"))
    @Test fun `3ds fallback exists`() = assertNotNull(physicalMediaIconRes("3ds"))
    @Test fun `nx fallback exists`() = assertNotNull(physicalMediaIconRes("nx"))
    @Test fun `tgfx16 fallback exists`() = assertNotNull(physicalMediaIconRes("tgfx16"))
    @Test fun `lynx fallback exists`() = assertNotNull(physicalMediaIconRes("lynx"))
    @Test fun `vb fallback exists`() = assertNotNull(physicalMediaIconRes("vb"))
    @Test fun `ws fallback exists`() = assertNotNull(physicalMediaIconRes("ws"))
    @Test fun `wsc fallback exists`() = assertNotNull(physicalMediaIconRes("wsc"))
    @Test fun `android fallback is null`() = assertNull(physicalMediaIconRes("android"))
    @Test fun `mame fallback is null`() = assertNull(physicalMediaIconRes("mame"))
}
