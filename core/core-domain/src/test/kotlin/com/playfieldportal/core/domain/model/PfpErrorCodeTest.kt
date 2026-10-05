package com.playfieldportal.core.domain.model

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The codes are a public contract: they appear in screenshots and logs, so the enum and the
 * approved registry (docs/dev/notification-error-codes.md) must never drift apart.
 */
class PfpErrorCodeTest {

    private val approved = listOf(
        "LN-1001", "LN-1002", "LN-2001", "LN-4001", "LN-4002", "LN-4003", "LN-9001",
        "SC-1001", "SC-1002", "SC-2001", "SC-2002", "SC-2003", "SC-2004", "SC-9001",
        "AR-2001", "AR-2002", "AR-2003", "AR-3001", "AR-3002", "AR-3003", "AR-9001",
        "AC-1001", "AC-2001", "AC-3001", "AC-3002", "AC-3003", "AC-3004",
        "MD-2001", "MD-9001",
        "BK-1001", "BK-2001", "BK-2002",
        "SY-9001",
    )

    @Test
    fun `the enum holds exactly the approved codes`() {
        assertEquals(approved.toSet(), PfpErrorCode.entries.map { it.id }.toSet())
    }

    @Test
    fun `the enum matches the registry document when it can be found`() {
        val doc = listOf(
            "../../docs/dev/notification-error-codes.md",
            "docs/dev/notification-error-codes.md",
        ).map(::File).firstOrNull { it.isFile } ?: return
        val documented = Regex("""^\| ([A-Z]{2}-\d{4}) \|""", RegexOption.MULTILINE)
            .findAll(doc.readText()).map { it.groupValues[1] }.toSet()
        assertEquals(documented, PfpErrorCode.entries.map { it.id }.toSet())
    }

    @Test
    fun `ids are unique and well formed`() {
        val ids = PfpErrorCode.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val shape = Regex("""^[A-Z]{2}-[12349]\d{3}$""")
        ids.forEach { assertTrue(shape.matches(it), "$it is not AA-BNNN") }
    }

    @Test
    fun `every code carries help text`() {
        PfpErrorCode.entries.forEach {
            assertTrue(it.title.isNotBlank(), "${it.id} has no title")
            assertTrue(it.whatYouCanDo.isNotBlank(), "${it.id} has no 'What you can do'")
        }
    }

    @Test
    fun `an unknown id falls back to the generic code`() {
        assertEquals(PfpErrorCode.SY_9001, PfpErrorCode.fromId("ZZ-0000"))
        assertEquals(PfpErrorCode.SY_9001, PfpErrorCode.fromId(null))
        assertEquals(PfpErrorCode.LN_4003, PfpErrorCode.fromId("LN-4003"))
    }
}
