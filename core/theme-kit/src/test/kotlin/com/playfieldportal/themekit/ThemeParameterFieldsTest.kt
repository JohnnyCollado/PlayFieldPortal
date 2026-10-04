package com.playfieldportal.themekit

import kotlin.test.Test
import kotlin.test.assertTrue

class ThemeParameterFieldsTest {

    private val manifestFields: Set<String> = PfpThemeManifest.serializer().descriptor.let { d ->
        (0 until d.elementsCount).map(d::getElementName).toSet()
    }

    @Test
    fun `every parameter field is a real manifest field`() {
        assertTrue(
            manifestFields.containsAll(ThemeParameterFields.ALL),
            "not manifest fields: ${ThemeParameterFields.ALL - manifestFields}",
        )
    }

    @Test
    fun `identity, bookkeeping and file-bound fields are not parameters`() {
        val notParameters = setOf("manifest", "name", "author", "description", "created", "updated", "source", "schemaVersion", "motionCrop")
        assertTrue((ThemeParameterFields.ALL intersect notParameters).isEmpty())
    }
}
