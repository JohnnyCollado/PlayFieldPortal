package com.playfieldportal.core.domain.model.emulatorkb

import com.playfieldportal.core.domain.model.IntentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorKbImportPlanTest {

    private fun emu(
        id: String,
        vararg packages: String,
        name: String = id,
        legacyIds: List<String> = emptyList(),
        launch: EmulatorKbLaunch = EmulatorKbLaunch(),
        signers: List<String> = emptyList(),
    ) = EmulatorKbEmulator(
        id = id,
        name = name,
        packageNames = packages.toList(),
        legacyIds = legacyIds,
        platformIds = listOf("psx"),
        launch = launch,
        signerSha256 = signers,
    )

    private fun effective(
        vararg entries: Pair<EmulatorKbEmulator, KbSource>,
        platforms: List<EffectiveKbPlatform> = emptyList(),
    ) = EffectiveKb(entries.map { (e, s) -> EffectiveKbEmulator(e, s, null) }, platforms, officialApplied = false)

    private fun validated(
        vararg emulators: EmulatorKbEmulator,
        platforms: List<EmulatorKbPlatform> = emptyList(),
        refused: List<KbItem.Rejected> = emptyList(),
    ) = EmulatorKbValidation(emulators.toList(), platforms, refused, emptyList())

    private fun build(
        effective: EffectiveKb,
        validated: EmulatorKbValidation,
        userEdited: Set<String> = emptySet(),
        probe: (String, List<String>) -> SignerState = { _, _ -> SignerState.NotInstalled },
    ) = EmulatorKbImportPlan.build(effective, validated, userEdited, probe)

    private val current = emu("a", "p.a", name = "Old")
    private val changed = emu("a", "p.a", name = "New", launch = EmulatorKbLaunch(activityClass = "p.A"))

    private fun applied(effective: EffectiveKb, plan: EmulatorKbImportPlan): EffectiveKb {
        val doc = plan.selectedDocument()
        val layer = EmulatorKbLayer(
            doc.emulators.map { (it as KbItem.Ok).value },
            doc.platforms.map { (it as KbItem.Ok).value },
            0,
        )
        return EmulatorKbMerge.merge(
            EmulatorKbLayer(effective.emulators.map { it.emulator }, effective.platforms.map { it.platform }, 1),
            null,
            listOf(UserKbLayer("f", layer)),
        )
    }

    @Test
    fun `new entry defaults checked, change and platform update unchecked`() {
        val plan = build(
            effective(current to KbSource.BuiltIn, platforms = listOf(EffectiveKbPlatform(EmulatorKbPlatform("psx", listOf("bin")), KbSource.BuiltIn, null))),
            validated(changed, emu("b", "p.b"), platforms = listOf(EmulatorKbPlatform("psx", listOf("bin", "chd")))),
        )
        val change = plan.items.filterIsInstance<ImportItem.Change>().single()
        val new = plan.items.filterIsInstance<ImportItem.New>().single()
        val update = plan.items.filterIsInstance<ImportItem.PlatformUpdate>().single()
        assertFalse(change.selected)
        assertTrue(new.selected)
        assertFalse(update.selected)
        assertEquals(listOf("chd"), update.addedExtensions)
        assertEquals(1, plan.selectedCount)
    }

    @Test
    fun `override declined keeps the current entry untouched`() {
        val eff = effective(current to KbSource.BuiltIn)
        val plan = build(eff, validated(changed))
        assertTrue(plan.selectedDocument().emulators.isEmpty())
        assertEquals(0, plan.selectedCount)
        assertEquals(eff.emulators.map { it.emulator }, applied(eff, plan).emulators.map { it.emulator })
    }

    @Test
    fun `override accepted replaces the entry`() {
        val eff = effective(current to KbSource.BuiltIn)
        val plan = build(eff, validated(changed)).toggle("emulator:a")
        assertEquals(1, plan.selectedCount)
        assertEquals(listOf(changed), applied(eff, plan).emulators.map { it.emulator })
    }

    @Test
    fun `new entry with Add declined adds nothing`() {
        val eff = effective(current to KbSource.BuiltIn)
        val plan = build(eff, validated(emu("b", "p.b"))).toggle("emulator:b")
        assertEquals(0, plan.selectedCount)
        assertTrue(plan.selectedDocument().emulators.isEmpty())
        assertEquals(listOf("a"), applied(eff, plan).emulators.map { it.emulator.id })
    }

    @Test
    fun `mixed selection yields exactly the chosen entries`() {
        val c = emu("c", "p.c", name = "C2")
        val eff = effective(current to KbSource.BuiltIn, emu("c", "p.c") to KbSource.Official)
        val plan = build(eff, validated(changed, emu("b", "p.b"), emu("d", "p.d"), c))
            .toggle("emulator:a") // accept override of a
            .toggle("emulator:d") // decline add of d
        // c stays declined; b stays accepted.
        val ids = plan.selectedDocument().emulators.map { (it as KbItem.Ok).value.id }
        assertEquals(listOf("a", "b"), ids)
        assertEquals(2, plan.selectedCount)
        val merged = applied(eff, plan).emulators.map { it.emulator.id }.sorted()
        assertEquals(listOf("a", "b", "c"), merged)
    }

    @Test
    fun `overridesOfficial is true against official and built-in, false against user`() {
        val eff = effective(
            current to KbSource.BuiltIn,
            emu("o", "p.o") to KbSource.Official,
            emu("u", "p.u") to KbSource.User("f1"),
        )
        val plan = build(eff, validated(changed, emu("o", "p.o", name = "x"), emu("u", "p.u", name = "x")))
        val byId = plan.items.filterIsInstance<ImportItem.Change>().associateBy { it.entry.id }
        assertTrue(byId.getValue("a").overridesOfficial)
        assertTrue(byId.getValue("o").overridesOfficial)
        assertFalse(byId.getValue("u").overridesOfficial)
    }

    @Test
    fun `userEdited follows the user-modified knowledge ids`() {
        val eff = effective(current to KbSource.BuiltIn)
        val edited = build(eff, validated(changed), userEdited = setOf("a")).items.single() as ImportItem.Change
        val plain = build(eff, validated(changed)).items.single() as ImportItem.Change
        assertTrue(edited.userEdited)
        assertFalse(plain.userEdited)
    }

    @Test
    fun `entry is matched by id then by package, never by legacy id`() {
        val eff = effective(emu("old_a", "p.a") to KbSource.BuiltIn, emu("z", "p.z") to KbSource.BuiltIn)
        val byLegacy = build(eff, validated(emu("new_a", "p.other", legacyIds = listOf("old_a"))))
        assertTrue(byLegacy.items.single() is ImportItem.New)
        val reverse = build(effective(emu("old_a", "p.a", legacyIds = listOf("new_a")) to KbSource.BuiltIn), validated(emu("new_a", "p.other")))
        assertTrue(reverse.items.single() is ImportItem.New)
        val byPackage = build(eff, validated(emu("fresh", "p.z", name = "Z")))
        assertEquals("z", (byPackage.items.single() as ImportItem.Change).current.id)
    }

    @Test
    fun `identical entry is Unchanged and not selectable`() {
        val eff = effective(current to KbSource.BuiltIn)
        val plan = build(eff, validated(current))
        assertTrue(plan.items.single() is ImportItem.Unchanged)
        val toggled = plan.toggle("emulator:a")
        assertEquals(0, toggled.selectedCount)
        assertTrue(toggled.selectedDocument().emulators.isEmpty())
    }

    @Test
    fun `diff names exactly the changed fields`() {
        val before = emu("a", "p.a", launch = EmulatorKbLaunch(activityClass = "p.A", flags = listOf("X")))
        val after = before.copy(
            launch = before.launch.copy(
                intentType = IntentType.COMPONENT,
                mimeType = "application/x",
                extras = mapOf("k" to "v"),
            ),
            packageNames = listOf("p.a", "p.b"),
        )
        val plan = build(effective(before to KbSource.BuiltIn), validated(after))
        val diff = (plan.items.single() as ImportItem.Change).diff
        assertEquals(setOf("Intent type", "MIME type", "Extras", "Packages"), diff.map { it.field }.toSet())
        val type = diff.first { it.field == "Intent type" }
        assertEquals(IntentType.ACTION_VIEW.name, type.before)
        assertEquals(IntentType.COMPONENT.name, type.after)
    }

    @Test
    fun `per-package override changes are in the diff`() {
        val before = emu("a", "p.a", "p.b")
        val after = before.copy(launchByPackage = mapOf("p.b" to EmulatorKbLaunch(activityClass = "p.B")))
        val diff = (build(effective(before to KbSource.BuiltIn), validated(after)).items.single() as ImportItem.Change).diff
        assertEquals(listOf("Per-package launch"), diff.map { it.field })
    }

    @Test
    fun `signer mismatch on an installed package is Blocked`() {
        val pinned = emu("n", "p.n", signers = listOf("ab".repeat(32)))
        val plan = build(effective(), validated(pinned)) { pkg, pins ->
            assertEquals("p.n", pkg)
            assertEquals(pinned.signerSha256, pins)
            SignerState.Mismatch
        }
        val blocked = plan.items.single() as ImportItem.Blocked
        assertEquals("n", blocked.id)
        assertTrue(blocked.reason.isNotBlank())
        assertEquals(0, plan.selectedCount)
        assertTrue(plan.toggle("emulator:n").selectedDocument().emulators.isEmpty())
    }

    @Test
    fun `signer match or not installed stays selectable and no probe without pins`() {
        val pinned = emu("n", "p.n", signers = listOf("ab".repeat(32)))
        for (state in listOf(SignerState.Matches, SignerState.NotInstalled)) {
            assertTrue(build(effective(), validated(pinned)) { _, _ -> state }.items.single() is ImportItem.New)
        }
        build(effective(), validated(emu("m", "p.m"))) { _, _ -> error("probe must not run") }
    }

    @Test
    fun `validator refusals are Blocked with their reason`() {
        val plan = build(effective(), validated(refused = listOf(KbItem.Rejected(0, "bad", "carries a custom command"))))
        val blocked = plan.items.single() as ImportItem.Blocked
        assertEquals("bad", blocked.id)
        assertEquals("carries a custom command", blocked.reason)
    }

    @Test
    fun `platform update toggles into the selected document`() {
        val eff = effective(
            platforms = listOf(EffectiveKbPlatform(EmulatorKbPlatform("psx", listOf("bin")), KbSource.BuiltIn, null)),
        )
        val plan = build(eff, validated(platforms = listOf(EmulatorKbPlatform("psx", listOf("bin", "chd")))))
        assertTrue(plan.selectedDocument().platforms.isEmpty())
        val on = plan.toggle("platform:psx")
        assertEquals(0, on.selectedCount) // platform updates are not emulators
        assertEquals(listOf("psx"), on.selectedDocument().platforms.map { (it as KbItem.Ok).value.id })
    }

    @Test
    fun `selected platform count counts ticked platform updates only`() {
        val eff = effective(
            current to KbSource.BuiltIn,
            platforms = listOf(EffectiveKbPlatform(EmulatorKbPlatform("psx", listOf("bin")), KbSource.BuiltIn, null)),
        )
        val plan = build(eff, validated(changed, emu("b", "p.b"), platforms = listOf(EmulatorKbPlatform("psx", listOf("bin", "chd")))))
        assertEquals(0, plan.selectedPlatformCount)
        val on = plan.toggle("platform:psx")
        assertEquals(1, on.selectedPlatformCount)
        assertEquals(1, on.selectedCount)
        assertEquals(0, on.toggle("platform:psx").selectedPlatformCount)
    }

    @Test
    fun `platform with no added extensions is omitted`() {
        val eff = effective(
            platforms = listOf(EffectiveKbPlatform(EmulatorKbPlatform("psx", listOf("bin", "chd")), KbSource.BuiltIn, null)),
        )
        val plan = build(eff, validated(platforms = listOf(EmulatorKbPlatform("psx", listOf("bin")))))
        assertTrue(plan.items.isEmpty())
    }

    @Test
    fun `selectedDocument is a version 0 knowledge document`() {
        val doc = build(effective(), validated(emu("b", "p.b"))).selectedDocument()
        assertEquals(EmulatorKbDecoder.FORMAT, doc.format)
        assertEquals(0L, doc.version)
    }

    @Test
    fun `a change that differs only in legacyIds has a diff`() {
        val before = emu("a", "p.a")
        val after = before.copy(legacyIds = listOf("old_a"))
        val diff = (build(effective(before to KbSource.BuiltIn), validated(after)).items.single() as ImportItem.Change).diff
        assertEquals(listOf("Legacy ids"), diff.map { it.field })
        assertEquals("old_a", diff.single().after)
    }

    @Test
    fun `per-package override diff describes the whole override`() {
        val before = emu("a", "p.a", "p.b")
        val override = EmulatorKbLaunch(
            intentType = IntentType.COMPONENT,
            activityClass = "p.B",
            action = "p.ACT",
            category = "p.CAT",
            extras = mapOf("k" to "v"),
            boolExtras = mapOf("flag" to true),
            arrayExtras = mapOf("arr" to listOf("x", "y")),
            flags = listOf("CLEAR_TASK"),
            mimeType = "application/x-test",
        )
        val after = before.copy(launchByPackage = mapOf("p.b" to override))
        val diff = (build(effective(before to KbSource.BuiltIn), validated(after)).items.single() as ImportItem.Change).diff
        val text = diff.single().after
        for (part in listOf("COMPONENT", "p.B", "p.ACT", "p.CAT", "k=v", "flag=true", "arr=x, y", "CLEAR_TASK", "application/x-test")) {
            assertTrue("'$text' should contain '$part'", text.contains(part))
        }
    }
}
