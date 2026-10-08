package com.playfieldportal.themekit

import com.playfieldportal.themekit.ThemeIconChoices.Choice
import com.playfieldportal.themekit.ThemeIconChoices.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeIconChoicesTest {

    private fun ref(group: Int, index: Int) = PtfIcons.SlotRef(group, index)

    @Test
    fun `slot keys follow the registry order and are labelled by display name`() {
        val inRegistry = CustomizableIcons.ALL.map { it.key }
        val shuffled = listOf(inRegistry[5], inRegistry[1], inRegistry[3])
        val section = ThemeIconChoices.sections(shuffled, emptyList()).single()
        assertEquals(ThemeIconChoices.THEME_ICONS_TITLE, section.title)
        assertEquals(
            listOf(inRegistry[1], inRegistry[3], inRegistry[5]).map {
                Choice(Source.Slot(it), CustomizableIcons.byKey(it)!!.displayName)
            },
            section.choices,
        )
    }

    @Test
    fun `unregistered slot keys are ignored`() {
        val key = CustomizableIcons.ALL.first().key
        val section = ThemeIconChoices.sections(listOf("nope", key), emptyList()).single()
        assertEquals(listOf(Source.Slot(key)), section.choices.map { it.source })
    }

    @Test
    fun `ptf refs are ordered by group then index`() {
        val section = ThemeIconChoices.sections(emptyList(), listOf(ref(4, 0), ref(2, 8), ref(3, 0), ref(2, 5))).single()
        assertEquals(ThemeIconChoices.PTF_ICONS_TITLE, section.title)
        assertEquals(
            listOf(ref(2, 5), ref(2, 8), ref(3, 0), ref(4, 0)).map { Source.Ptf(it) },
            section.choices.map { it.source },
        )
    }

    @Test
    fun `named refs use their PSP label and unnamed ones count Icon 1, Icon 2 skipping named`() {
        val section = ThemeIconChoices.sections(
            emptyList(),
            listOf(ref(2, 2), ref(2, 5), ref(2, 6), ref(2, 8), ref(3, 12)),
        ).single()
        assertEquals(listOf("Icon 1", "TV", "Icon 2", "Extras", "Icon 3"), section.choices.map { it.label })
    }

    @Test
    fun `no ptf refs gives one section, no slot keys only the ptf section, both empty nothing`() {
        val key = CustomizableIcons.ALL.first().key
        assertEquals(
            listOf(ThemeIconChoices.THEME_ICONS_TITLE),
            ThemeIconChoices.sections(listOf(key), emptyList()).map { it.title },
        )
        assertEquals(
            listOf(ThemeIconChoices.PTF_ICONS_TITLE),
            ThemeIconChoices.sections(emptyList(), listOf(ref(2, 5))).map { it.title },
        )
        assertEquals(
            listOf(ThemeIconChoices.THEME_ICONS_TITLE, ThemeIconChoices.PTF_ICONS_TITLE),
            ThemeIconChoices.sections(listOf(key), listOf(ref(2, 5))).map { it.title },
        )
        assertTrue(ThemeIconChoices.sections(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `slots with identical art collapse into the first slot in registry order`() {
        val a = CustomizableIcons.ALL[0].key
        val b = CustomizableIcons.ALL[1].key
        val c = CustomizableIcons.ALL[2].key
        val art = mapOf(a to "x", b to "y", c to "x")
        val section = ThemeIconChoices.sections(listOf(c, b, a), emptyList(), artKey = art::get).single()
        assertEquals(listOf(Source.Slot(a), Source.Slot(b)), section.choices.map { it.source })
    }

    @Test
    fun `a null art key never collapses`() {
        val a = CustomizableIcons.ALL[0].key
        val b = CustomizableIcons.ALL[1].key
        val section = ThemeIconChoices.sections(listOf(a, b), emptyList(), artKey = { null }).single()
        assertEquals(2, section.choices.size)
    }
}
