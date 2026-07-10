package com.playfieldportal.feature.artwork.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The XML walk itself needs an Android pull parser (validated on device); the pure field
// mapping — ES release dates to years — is locked down here.
class EsDeGamelistParserTest {

    @Test
    fun `ES releasedate format maps to a year`() {
        assertEquals(2001, EsDeGamelistParser.yearOf("20011217T000000"))
        assertEquals(1996, EsDeGamelistParser.yearOf("19960923T000000"))
        assertEquals(2001, EsDeGamelistParser.yearOf(" 20011217T000000 "))
    }

    @Test
    fun `garbage release dates are rejected`() {
        assertNull(EsDeGamelistParser.yearOf(null))
        assertNull(EsDeGamelistParser.yearOf(""))
        assertNull(EsDeGamelistParser.yearOf("unknown"))
        assertNull(EsDeGamelistParser.yearOf("0000"))       // below the sanity floor
        assertNull(EsDeGamelistParser.yearOf("9999"))       // above the sanity ceiling
    }
}
