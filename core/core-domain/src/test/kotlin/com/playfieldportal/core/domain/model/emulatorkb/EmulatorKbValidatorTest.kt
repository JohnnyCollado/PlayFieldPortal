package com.playfieldportal.core.domain.model.emulatorkb

import com.playfieldportal.core.domain.model.IntentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorKbValidatorTest {

    private val known = setOf("psx", "ps2")
    private val self = "com.playfieldportal.app"

    private fun launch(
        intentType: IntentType = IntentType.COMPONENT,
        activityClass: String? = "org.example.emu.Main",
        extras: Map<String, String> = emptyMap(),
        boolExtras: Map<String, Boolean> = emptyMap(),
        arrayExtras: Map<String, List<String>> = emptyMap(),
        action: String? = null,
        category: String? = null,
        flags: List<String> = emptyList(),
        mimeType: String? = null,
        dataUri: String? = null,
    ) = EmulatorKbLaunch(
        intentType = intentType,
        activityClass = activityClass,
        extras = extras,
        boolExtras = boolExtras,
        arrayExtras = arrayExtras,
        action = action,
        category = category,
        flags = flags,
        mimeType = mimeType,
        dataUri = dataUri,
    )

    private fun emu(
        id: String = "emu",
        packages: List<String> = listOf("org.example.emu"),
        platformIds: List<String> = listOf("psx"),
        launch: EmulatorKbLaunch = launch(),
        launchByPackage: Map<String, EmulatorKbLaunch> = emptyMap(),
        signers: List<String> = emptyList(),
        legacyIds: List<String> = emptyList(),
        name: String = "Emu",
    ) = EmulatorKbEmulator(
        id = id,
        name = name,
        packageNames = packages,
        legacyIds = legacyIds,
        platformIds = platformIds,
        launch = launch,
        launchByPackage = launchByPackage,
        signerSha256 = signers,
    )

    private fun document(
        emulators: List<EmulatorKbEmulator> = emptyList(),
        platforms: List<EmulatorKbPlatform> = emptyList(),
        rejectedEmulators: List<KbItem.Rejected> = emptyList(),
    ) = EmulatorKbDocument(
        format = EmulatorKbDecoder.FORMAT,
        schemaVersion = 1,
        version = 1,
        label = "t",
        minAppVersion = 0,
        emulators = emulators.map { KbItem.Ok(it) } + rejectedEmulators,
        platforms = platforms.map { KbItem.Ok(it) },
    )

    private fun validate(doc: EmulatorKbDocument) = EmulatorKbValidator.validate(doc, known, self)

    private fun validate(e: EmulatorKbEmulator) = validate(document(listOf(e)))

    private fun assertRefused(e: EmulatorKbEmulator, reasonPart: String) {
        val r = validate(e)
        assertTrue("expected refusal, was admitted", r.emulators.isEmpty())
        assertEquals(1, r.refusedEmulators.size)
        val reason = r.refusedEmulators[0].reason
        assertTrue("reason '$reason' should mention '$reasonPart'", reason.contains(reasonPart, ignoreCase = true))
    }

    private fun assertAdmitted(e: EmulatorKbEmulator) {
        val r = validate(e)
        assertEquals("refused: ${r.refusedEmulators}", listOf(e), r.emulators)
    }

    private fun extrasLaunch(extras: Map<String, String>) = launch(extras = extras)

    // -- dataUri (deep-link launches) ---------------------------------------------

    @Test
    fun `a deep link carrying the game is admitted`() =
        assertAdmitted(emu(launch = launch(dataUri = "x360mobile://launch?uri={rom_file_uri_encoded}")))

    @Test
    fun `a deep link with no game placeholder is refused`() =
        assertRefused(emu(launch = launch(dataUri = "x360mobile://launch")), "data URI")

    @Test
    fun `a deep link may hold only one placeholder`() =
        assertRefused(
            emu(launch = launch(dataUri = "emu://launch?uri={rom_file_uri_encoded}&t={title_id}")),
            "data URI",
        )

    @Test
    fun `file content web and intent data uris are refused`() {
        for (uri in listOf(
            "file://{rom_file_uri_encoded}", "content://x/{rom_file_uri_encoded}",
            "https://example.com/?u={rom_file_uri_encoded}", "intent://x?u={rom_file_uri_encoded}",
            "javascript:{rom_file_uri_encoded}",
        )) {
            assertRefused(emu(launch = launch(dataUri = uri)), "data URI")
        }
    }

    @Test
    fun `a deep link on a view launch is refused`() =
        assertRefused(
            emu(launch = launch(intentType = IntentType.ACTION_VIEW, activityClass = null,
                dataUri = "x360mobile://launch?uri={rom_file_uri_encoded}")),
            "data URI",
        )

    // -- Accepted ---------------------------------------------------------------

    @Test
    fun `fully valid entry passes`() {
        assertAdmitted(
            emu(
                launch = launch(
                    extras = mapOf("bootPath" to "{rom_uri}", "mode" to "fast-1.0_a,b=c+d@e f"),
                    boolExtras = mapOf("resume" to false),
                    arrayExtras = mapOf("args" to listOf("-v", "{rom_path}")),
                    action = "org.example.emu.LAUNCH",
                    category = "android.intent.category.LAUNCHER",
                    flags = listOf("NEW_TASK", "CLEAR_TOP", "CLEAR_TASK"),
                    mimeType = "application/x-iso9660-image",
                ),
                signers = listOf("a".repeat(64)),
                legacyIds = listOf("emu_old"),
            ),
        )
    }

    @Test
    fun `action view without an activity passes`() {
        assertAdmitted(emu(launch = launch(intentType = IntentType.ACTION_VIEW, activityClass = null)))
    }

    // -- Launch type ------------------------------------------------------------

    @Test
    fun `custom command is refused`() =
        assertRefused(emu(launch = launch(intentType = IntentType.CUSTOM_COMMAND)), "command")

    @Test
    fun `shortcut is refused`() =
        assertRefused(emu(launch = launch(intentType = IntentType.SHORTCUT)), "shortcut")

    @Test
    fun `component without an activity is refused`() =
        assertRefused(emu(launch = launch(activityClass = null)), "activity")

    @Test
    fun `component with a blank activity is refused`() =
        assertRefused(emu(launch = launch(activityClass = " ")), "activity")

    // -- Packages ---------------------------------------------------------------

    @Test
    fun `own package is refused`() = assertRefused(emu(packages = listOf(self)), "own package")

    @Test
    fun `playfieldportal prefix is refused`() =
        assertRefused(emu(packages = listOf("com.playfieldportal.x")), "playfieldportal")

    @Test
    fun `android prefix is refused`() =
        assertRefused(emu(packages = listOf("android.app.foo")), "system")

    @Test
    fun `com android settings is refused`() =
        assertRefused(emu(packages = listOf("com.android.settings")), "system")

    @Test
    fun `com google android prefix is refused`() =
        assertRefused(emu(packages = listOf("com.google.android.gms")), "system")

    @Test
    fun `a similar non system prefix is allowed`() =
        assertAdmitted(emu(packages = listOf("com.androidemu.nes")))

    @Test
    fun `bad package name is refused`() =
        assertRefused(emu(packages = listOf("not a package")), "package")

    @Test
    fun `no packages is refused`() = assertRefused(emu(packages = emptyList()), "package")

    @Test
    fun `more than 8 packages is refused`() =
        assertRefused(emu(packages = (1..9).map { "org.example.p$it" }), "package")

    // -- Identity ---------------------------------------------------------------

    @Test
    fun `bad id is refused`() = assertRefused(emu(id = "Bad Id"), "id")

    @Test
    fun `bad legacy id is refused`() = assertRefused(emu(legacyIds = listOf("Bad/Id")), "legacy")

    @Test
    fun `over long name is refused`() = assertRefused(emu(name = "n".repeat(65)), "name")

    @Test
    fun `blank name is refused`() = assertRefused(emu(name = " "), "name")

    // -- Activity, action, category, flags, MIME --------------------------------

    @Test
    fun `bad activity class is refused`() =
        assertRefused(emu(launch = launch(activityClass = "org/example/Main")), "activity")

    @Test
    fun `over long activity class is refused`() =
        assertRefused(emu(launch = launch(activityClass = "a." + "b".repeat(200))), "activity")

    @Test
    fun `bad action is refused`() =
        assertRefused(emu(launch = launch(action = "do something")), "action")

    @Test
    fun `bad category is refused`() =
        assertRefused(emu(launch = launch(category = "cat:egory")), "category")

    @Test
    fun `disallowed flag is refused`() =
        assertRefused(emu(launch = launch(flags = listOf("GRANT_WRITE_URI_PERMISSION"))), "flag")

    @Test
    fun `bad mime type is refused`() =
        assertRefused(emu(launch = launch(mimeType = "notamime")), "mime")

    // -- Extras -----------------------------------------------------------------

    @Test
    fun `literal with a slash is refused`() =
        assertRefused(emu(launch = extrasLaunch(mapOf("p" to "/sdcard/x"))), "extra")

    @Test
    fun `literal with a colon is refused`() =
        assertRefused(emu(launch = extrasLaunch(mapOf("p" to "a:b"))), "extra")

    @Test
    fun `mixed placeholder and literal is refused`() =
        assertRefused(emu(launch = extrasLaunch(mapOf("p" to "file://{rom_path}"))), "extra")

    @Test
    fun `unknown placeholder is refused`() =
        assertRefused(emu(launch = extrasLaunch(mapOf("p" to "{secret}"))), "extra")

    @Test
    fun `over long literal is refused`() =
        assertRefused(emu(launch = extrasLaunch(mapOf("p" to "a".repeat(65)))), "extra")

    @Test
    fun `every placeholder is accepted as an extra`() {
        val all = listOf(
            "{rom_path}", "{rom_uri}", "{rom_name}", "{rom_dir}", "{core_path}",
            "{config_path}", "{package}", "{platform}", "{title_id}",
        )
        assertAdmitted(emu(launch = launch(extras = all.withIndex().associate { "k${it.index}" to it.value })))
    }

    @Test
    fun `more than 16 string extras is refused`() =
        assertRefused(emu(launch = extrasLaunch((1..17).associate { "k$it" to "v" })), "extra")

    @Test
    fun `16 string extras is allowed`() =
        assertAdmitted(emu(launch = extrasLaunch((1..16).associate { "k$it" to "v" })))

    @Test
    fun `more than 16 bool extras is refused`() =
        assertRefused(emu(launch = launch(boolExtras = (1..17).associate { "k$it" to true })), "extra")

    @Test
    fun `more than 16 array extras is refused`() =
        assertRefused(emu(launch = launch(arrayExtras = (1..17).associate { "k$it" to listOf("a") })), "extra")

    @Test
    fun `more than 8 array items is refused`() =
        assertRefused(emu(launch = launch(arrayExtras = mapOf("a" to (1..9).map { "v" }))), "extra")

    @Test
    fun `array item that is a path is refused`() =
        assertRefused(emu(launch = launch(arrayExtras = mapOf("a" to listOf("-f", "/data/x")))), "extra")

    // -- Signers ----------------------------------------------------------------

    @Test
    fun `malformed signer is refused`() =
        assertRefused(emu(signers = listOf("xyz")), "signer")

    @Test
    fun `signer of the wrong length is refused`() =
        assertRefused(emu(signers = listOf("ab".repeat(31))), "signer")

    @Test
    fun `colon separated signer is stripped and lowercased`() {
        val colon = "AB".repeat(32).chunked(2).joinToString(":")
        val r = validate(emu(signers = listOf(colon)))
        assertEquals(listOf("ab".repeat(32)), r.emulators.single().signerSha256)
    }

    // -- Duplicates -------------------------------------------------------------

    @Test
    fun `duplicate id refuses the second entry`() {
        val r = validate(
            document(listOf(emu(id = "same"), emu(id = "same", packages = listOf("org.example.other")))),
        )
        assertEquals(1, r.emulators.size)
        assertEquals(1, r.refusedEmulators.size)
        assertEquals(1, r.refusedEmulators[0].index)
        assertTrue(r.refusedEmulators[0].reason.contains("duplicate", ignoreCase = true))
    }

    @Test
    fun `package claimed twice refuses the second entry`() {
        val r = validate(document(listOf(emu(id = "one"), emu(id = "two"))))
        assertEquals(listOf("one"), r.emulators.map { it.id })
        assertTrue(r.refusedEmulators.single().reason.contains("package", ignoreCase = true))
    }

    @Test
    fun `package repeated inside one entry is refused`() =
        assertRefused(emu(packages = listOf("org.example.a", "org.example.a")), "package")

    // -- launchByPackage (AD-15) ------------------------------------------------

    @Test
    fun `override for one of the entry packages passes`() {
        assertAdmitted(
            emu(
                packages = listOf("org.example.emu", "org.example.emu.ea"),
                launchByPackage = mapOf(
                    "org.example.emu.ea" to launch(intentType = IntentType.ACTION_VIEW, activityClass = null),
                ),
            ),
        )
    }

    @Test
    fun `override key outside packageNames is refused`() =
        assertRefused(emu(launchByPackage = mapOf("org.example.other" to launch())), "override")

    @Test
    fun `override that fails a launch rule is refused`() =
        assertRefused(
            emu(launchByPackage = mapOf("org.example.emu" to launch(intentType = IntentType.CUSTOM_COMMAND))),
            "command",
        )

    @Test
    fun `override with a path literal is refused`() =
        assertRefused(
            emu(launchByPackage = mapOf("org.example.emu" to launch(extras = mapOf("p" to "/x")))),
            "extra",
        )

    @Test
    fun `more than 8 overrides is refused`() {
        val pkgs = (1..8).map { "org.example.p$it" }
        // 8 packages max, so exceed the override cap with a ninth key (outside packageNames is also
        // refused, but the cap reason must be reported for a count over 8).
        val overrides = (1..9).associate { "org.example.p$it" to launch() }
        assertRefused(emu(packages = pkgs, launchByPackage = overrides), "override")
    }

    // -- Platforms (AD-3) -------------------------------------------------------

    @Test
    fun `unknown platform ids are dropped from an entry`() {
        val r = validate(emu(platformIds = listOf("psx", "naomi", "ps2")))
        assertEquals(listOf("psx", "ps2"), r.emulators.single().platformIds)
        assertTrue(r.refusedEmulators.isEmpty())
    }

    @Test
    fun `entry with only unknown platforms is omitted not refused`() {
        val r = validate(emu(platformIds = listOf("naomi", "symbian")))
        assertTrue(r.emulators.isEmpty())
        assertTrue(r.refusedEmulators.isEmpty())
    }

    @Test
    fun `omitted entry does not claim its package`() {
        val r = validate(
            document(listOf(emu(id = "inert", platformIds = listOf("naomi")), emu(id = "real"))),
        )
        assertEquals(listOf("real"), r.emulators.map { it.id })
        assertTrue(r.refusedEmulators.isEmpty())
    }

    @Test
    fun `platform item for an unknown id is skipped`() {
        val r = validate(document(platforms = listOf(EmulatorKbPlatform("naomi", listOf("zip")))))
        assertTrue(r.platforms.isEmpty())
        assertTrue(r.refusedPlatforms.isEmpty())
    }

    @Test
    fun `platform item for a known id passes`() {
        val p = EmulatorKbPlatform("psx", listOf("cue", "bin", "a1"))
        assertEquals(listOf(p), validate(document(platforms = listOf(p))).platforms)
    }

    @Test
    fun `uppercase extension is refused`() = assertPlatformRefused(listOf("ISO"), "extension")

    @Test
    fun `dotted extension is refused`() = assertPlatformRefused(listOf(".iso"), "extension")

    @Test
    fun `over long extension is refused`() = assertPlatformRefused(listOf("toolongext1"), "extension")

    @Test
    fun `more than 32 extensions is refused`() =
        assertPlatformRefused((1..33).map { "e$it" }, "extension")

    @Test
    fun `32 extensions is allowed`() {
        val p = EmulatorKbPlatform("psx", (1..32).map { "e$it" })
        assertEquals(listOf(p), validate(document(platforms = listOf(p))).platforms)
    }

    @Test
    fun `duplicate platform id refuses the second item`() {
        val r = validate(
            document(platforms = listOf(EmulatorKbPlatform("psx", listOf("a")), EmulatorKbPlatform("psx", listOf("b")))),
        )
        assertEquals(1, r.platforms.size)
        assertEquals(1, r.refusedPlatforms.size)
    }

    private fun assertPlatformRefused(extensions: List<String>, reasonPart: String) {
        val r = validate(document(platforms = listOf(EmulatorKbPlatform("psx", extensions))))
        assertTrue(r.platforms.isEmpty())
        assertTrue(r.refusedPlatforms.single().reason.contains(reasonPart, ignoreCase = true))
    }

    // -- Decoder refusals pass through ------------------------------------------

    @Test
    fun `items the decoder refused are reported`() {
        val rejected = KbItem.Rejected(3, "bad", "has an unknown key 'x'")
        val r = validate(document(listOf(emu()), rejectedEmulators = listOf(rejected)))
        assertEquals(1, r.emulators.size)
        assertEquals(listOf(rejected), r.refusedEmulators)
    }

    @Test
    fun `extras keys outside the key pattern are refused`() {
        for (key in listOf("", "1abc", "has space", "a/b", "a:b", "k".repeat(101))) {
            assertRefused(emu(launch = launch(extras = mapOf(key to "v"))), "key")
            assertRefused(emu(launch = launch(boolExtras = mapOf(key to true))), "key")
            assertRefused(emu(launch = launch(arrayExtras = mapOf(key to listOf("v")))), "key")
        }
    }

    @Test
    fun `a bad extras key in a launch override is refused`() =
        assertRefused(
            emu(launchByPackage = mapOf("org.example.emu" to launch(extras = mapOf("bad key" to "v")))),
            "key",
        )

    @Test
    fun `dotted dashed and underscored extras keys are allowed`() =
        assertAdmitted(emu(launch = launch(extras = mapOf("org.x.Key-1_a" to "v", "A" to "v"))))

    @Test
    fun `vendor package prefixes are refused`() {
        val prefixes = listOf(
            "com.google.", "com.sec.android.", "com.samsung.android.", "com.miui.", "com.xiaomi.",
            "com.huawei.", "com.oplus.", "com.coloros.", "com.oneplus.",
        )
        for (p in prefixes) assertRefused(emu(packages = listOf(p + "thing")), "system")
    }
}
