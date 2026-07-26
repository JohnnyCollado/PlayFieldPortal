package com.playfieldportal.feature.achievements.provider.vita

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TropSfmTest {

    private val xml = """
        <!--Sce-Np-Trophy-Signature: deadbeef-->
        <trophyconf version="1.1" platform="psp2">
         <npcommid>NPWR02979_00</npcommid>
         <title-name>Disgaea 3: Absence of Detention</title-name>
         <trophy id="000" hidden="no" ttype="P" pid="-1">
          <name>Platinum Trophy</name>
          <detail>Congratulations on getting all the trophies!</detail>
         </trophy>
         <trophy id="004" hidden="yes" ttype="B" pid="000">
          <name>A Single Step</name>
          <detail>Welcome to Disgaea 3!&#x0a;Enjoy &amp; good luck!</detail>
         </trophy>
        </trophyconf>
    """.trimIndent()

    @Test
    fun `parses set metadata and each trophy's grade, hidden flag, and text`() {
        val set = TropSfm.parse(xml)

        assertEquals("NPWR02979_00", set.npCommId)
        assertEquals("Disgaea 3: Absence of Detention", set.titleName)
        assertEquals(2, set.trophies.size)

        val platinum = set.trophies[0]
        assertEquals(0, platinum.id)
        assertEquals("Platinum Trophy", platinum.name)
        assertEquals(TropUsrParser.Grade.PLATINUM, platinum.grade)
        assertFalse(platinum.hidden)

        val bronze = set.trophies[1]
        assertEquals(4, bronze.id)
        assertEquals("A Single Step", bronze.name)
        assertEquals(TropUsrParser.Grade.BRONZE, bronze.grade)
        assertTrue(bronze.hidden)
        // entity decode: &#x0a; -> newline, &amp; -> &
        assertTrue(bronze.detail.contains("\n"), "numeric entity not decoded")
        assertTrue(bronze.detail.contains("Enjoy & good luck!"), "named entity not decoded")
    }

    @Test
    fun `empty or non-sfo input yields an empty set`() {
        assertEquals(0, TropSfm.parse("not xml").trophies.size)
    }
}
