package com.playfieldportal.themekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class XmbLayoutSpecCodecTest {

    @Test
    fun `round-trips a customized spec`() {
        val spec = XmbLayoutSpec(barTopFraction = 0.22f, itemTextSp = 16f)
        assertEquals(spec, XmbLayoutSpecCodec.decode(XmbLayoutSpecCodec.encode(spec)))
    }

    @Test
    fun `default spec round-trips unchanged`() {
        assertEquals(
            XmbLayoutSpec.DEFAULT,
            XmbLayoutSpecCodec.decode(XmbLayoutSpecCodec.encode(XmbLayoutSpec.DEFAULT)),
        )
    }

    @Test
    fun `malformed input decodes to null`() {
        assertNull(XmbLayoutSpecCodec.decode(null))
        assertNull(XmbLayoutSpecCodec.decode(""))
        assertNull(XmbLayoutSpecCodec.decode("not json"))
        assertNull(XmbLayoutSpecCodec.decode("""{"barTopFraction":"a string"}"""))
    }

    @Test
    fun `unknown keys are ignored and missing keys default`() {
        val decoded = XmbLayoutSpecCodec.decode("""{"barTopFraction":0.2,"futureField":true}""")
        assertEquals(0.2f, decoded?.barTopFraction)
        assertEquals(XmbLayoutSpec.DEFAULT.itemIconDp, decoded?.itemIconDp)
    }

    @Test
    fun `hostile values clamp into safe ranges`() {
        val hostile = XmbLayoutSpec(
            barTopFraction = 9f,
            contentTopPaddingDp = -50f,
            categoryIconSelectedDp = 100_000f,
            itemTextSp = 0f,
            leftAnchorExtraDp = -999f,
            previousItemRiseRows = 40f,
        )
        val safe = XmbLayoutSpecCodec.sanitize(hostile)
        assertEquals(XmbLayoutSpecCodec.BAR_TOP_MAX, safe.barTopFraction)
        assertEquals(0f, safe.contentTopPaddingDp)
        assertEquals(160f, safe.categoryIconSelectedDp)
        assertEquals(8f, safe.itemTextSp)
        assertEquals(-60f, safe.leftAnchorExtraDp)
        assertEquals(2f, safe.previousItemRiseRows)
    }

    @Test
    fun `NaN and infinity fall back to the field default before clamping`() {
        val safe = XmbLayoutSpecCodec.sanitize(
            XmbLayoutSpec(barTopFraction = Float.NaN, itemIconDp = Float.POSITIVE_INFINITY),
        )
        assertEquals(XmbLayoutSpec.DEFAULT.barTopFraction, safe.barTopFraction)
        assertEquals(XmbLayoutSpec.DEFAULT.itemIconDp, safe.itemIconDp)
    }

    @Test
    fun `decode clamps hostile json values`() {
        val decoded = XmbLayoutSpecCodec.decode("""{"barTopFraction":0.9}""")
        assertEquals(XmbLayoutSpecCodec.BAR_TOP_MAX, decoded?.barTopFraction)
    }
}
