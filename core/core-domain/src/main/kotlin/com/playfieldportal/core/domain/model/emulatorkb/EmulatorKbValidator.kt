package com.playfieldportal.core.domain.model.emulatorkb

import com.playfieldportal.core.domain.model.EmulatorProfileAdmission
import com.playfieldportal.core.domain.model.IntentType
import com.playfieldportal.core.domain.model.LaunchTemplate

/** What [EmulatorKbValidator.validate] kept and turned away. Refusals carry their reason (AD-4). */
data class EmulatorKbValidation(
    val emulators: List<EmulatorKbEmulator>,
    val platforms: List<EmulatorKbPlatform>,
    val refusedEmulators: List<KbItem.Rejected>,
    val refusedPlatforms: List<KbItem.Rejected>,
)

/**
 * Semantic rules for a decoded KB file (AD-2, AD-3). One validator for every source (built-in,
 * official, user files and export output), so a recipe that cannot be imported cannot be exported.
 *
 * Package and flag rules are [EmulatorProfileAdmission]'s own, not a copy. Pure Kotlin.
 */
object EmulatorKbValidator {
    const val MAX_PACKAGES = 8
    const val MAX_NAME_CHARS = 64
    const val MAX_IDENTIFIER_CHARS = 200
    const val MAX_EXTRAS_PER_KIND = 16
    const val MAX_ARRAY_ITEMS = 8
    const val MAX_OVERRIDES = 8
    const val MAX_EXTENSIONS = 32

    private val ID = Regex("[a-z0-9_]{2,48}")

    // Dotted Java identifier, used for activity classes, actions and categories.
    private val DOTTED_IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_$]*(\.[A-Za-z_][A-Za-z0-9_$]*)*""")

    // No '/' and no ':' so a literal can never be a path or a URI.
    private val EXTRA_LITERAL = Regex("""[A-Za-z0-9._,=+@ -]{0,64}""")

    private val MIME_TYPE = Regex("""[A-Za-z0-9][A-Za-z0-9._+-]{0,126}/[A-Za-z0-9][A-Za-z0-9._+-]{0,126}""")
    // Extras key names: a letter first, then letters, digits and . _ -.
    private val EXTRA_KEY = Regex("""[A-Za-z][A-Za-z0-9_.-]{0,99}""")
    private val EXTENSION = Regex("[a-z0-9]{1,10}")
    private val SIGNER = Regex("[0-9a-f]{64}")

    private val PLACEHOLDERS = setOf(
        LaunchTemplate.ROM_PATH, LaunchTemplate.ROM_URI, LaunchTemplate.ROM_NAME, LaunchTemplate.ROM_DIR,
        LaunchTemplate.CORE_PATH, LaunchTemplate.CONFIG_PATH, LaunchTemplate.PACKAGE, LaunchTemplate.PLATFORM,
        LaunchTemplate.TITLE_ID,
    )

    // A recipe aimed at the platform or this app would turn PFP into a confused deputy.
    private val SYSTEM_PACKAGE_PREFIXES = listOf(
        "android.", "com.android.", "com.google.", "com.sec.android.", "com.samsung.android.",
        "com.miui.", "com.xiaomi.", "com.huawei.", "com.oplus.", "com.coloros.", "com.oneplus.",
    )
    private const val OWN_PACKAGE_PREFIX = "com.playfieldportal."

    /**
     * [knownPlatformIds] is every seeded platform id plus each alias expansion (AD-3). A platform id
     * outside it is "not applicable here", not invalid: it is dropped, and an entry left with no
     * platform is omitted without a refusal.
     */
    fun validate(
        document: EmulatorKbDocument,
        knownPlatformIds: Set<String>,
        selfPackage: String?,
    ): EmulatorKbValidation {
        val emulators = mutableListOf<EmulatorKbEmulator>()
        val refusedEmulators = mutableListOf<KbItem.Rejected>()
        val claimedIds = mutableSetOf<String>()
        val claimedPackages = mutableSetOf<String>()

        document.emulators.forEachIndexed { index, item ->
            when (item) {
                is KbItem.Rejected -> refusedEmulators += item
                is KbItem.Ok -> {
                    val emulator = item.value
                    val refusal = emulatorRefusal(emulator, selfPackage)
                    if (refusal != null) {
                        refusedEmulators += KbItem.Rejected(index, emulator.id, refusal)
                        return@forEachIndexed
                    }
                    val platformIds = emulator.platformIds.filter { it in knownPlatformIds }
                    // Inert here (a newer file written for platforms this app lacks): omit, and claim nothing.
                    if (platformIds.isEmpty()) return@forEachIndexed
                    val duplicate = when {
                        emulator.id in claimedIds -> "has a duplicate id '${emulator.id}'"
                        else -> emulator.packageNames.firstOrNull { it in claimedPackages }
                            ?.let { "claims package '$it', which an earlier entry already claims" }
                    }
                    if (duplicate != null) {
                        refusedEmulators += KbItem.Rejected(index, emulator.id, duplicate)
                        return@forEachIndexed
                    }
                    claimedIds += emulator.id
                    claimedPackages += emulator.packageNames
                    emulators += emulator.copy(
                        platformIds = platformIds,
                        signerSha256 = emulator.signerSha256.map(::normalizeSigner),
                    )
                }
            }
        }

        val platforms = mutableListOf<EmulatorKbPlatform>()
        val refusedPlatforms = mutableListOf<KbItem.Rejected>()
        val claimedPlatforms = mutableSetOf<String>()
        document.platforms.forEachIndexed { index, item ->
            when (item) {
                is KbItem.Rejected -> refusedPlatforms += item
                is KbItem.Ok -> {
                    val platform = item.value
                    if (platform.id !in knownPlatformIds) return@forEachIndexed
                    val refusal = platformRefusal(platform, claimedPlatforms)
                    if (refusal != null) {
                        refusedPlatforms += KbItem.Rejected(index, platform.id, refusal)
                    } else {
                        claimedPlatforms += platform.id
                        platforms += platform
                    }
                }
            }
        }
        return EmulatorKbValidation(emulators, platforms, refusedEmulators, refusedPlatforms)
    }

    private fun emulatorRefusal(e: EmulatorKbEmulator, selfPackage: String?): String? {
        if (!ID.matches(e.id)) return "has an invalid id (use 2-48 of a-z, 0-9, _)"
        e.legacyIds.firstOrNull { !ID.matches(it) }?.let { return "has an invalid legacy id '$it'" }
        if (e.name.isBlank() || e.name.length > MAX_NAME_CHARS) {
            return "needs a name of 1-$MAX_NAME_CHARS characters"
        }
        if (e.packageNames.isEmpty() || e.packageNames.size > MAX_PACKAGES) {
            return "needs 1-$MAX_PACKAGES package names"
        }
        if (e.packageNames.toSet().size != e.packageNames.size) return "lists the same package twice"
        e.packageNames.firstNotNullOfOrNull { packageRefusal(it, selfPackage) }?.let { return it }

        launchRefusal(e.launch)?.let { return it }

        if (e.launchByPackage.size > MAX_OVERRIDES) return "has more than $MAX_OVERRIDES launch overrides"
        for ((pkg, launch) in e.launchByPackage) {
            if (pkg !in e.packageNames) return "has a launch override for '$pkg', which is not one of its packages"
            launchRefusal(launch)?.let { return "has a launch override for '$pkg' that $it" }
        }

        e.signerSha256.firstOrNull { !SIGNER.matches(normalizeSigner(it)) }
            ?.let { return "has a malformed signer digest (expected 64 hex digits)" }
        return null
    }

    private fun packageRefusal(pkg: String, selfPackage: String?): String? = when {
        !EmulatorProfileAdmission.PACKAGE_NAME.matches(pkg) -> "package name '$pkg' is not a valid Android package"
        selfPackage != null && pkg == selfPackage -> "targets this app's own package"
        pkg.startsWith(OWN_PACKAGE_PREFIX) -> "targets a playfieldportal package ('$pkg')"
        SYSTEM_PACKAGE_PREFIXES.any { pkg.startsWith(it) } -> "targets a system package ('$pkg')"
        else -> null
    }

    /** Reasons read as a verb phrase after the subject, like the other refusals. */
    private fun launchRefusal(l: EmulatorKbLaunch): String? {
        when (l.intentType) {
            IntentType.CUSTOM_COMMAND -> return "carries a custom command"
            IntentType.SHORTCUT -> return "is a shortcut launch, which knowledge files cannot use"
            IntentType.COMPONENT ->
                if (l.activityClass.isNullOrBlank()) return "is a component intent with no activity class"
            IntentType.ACTION_VIEW -> Unit
        }
        l.activityClass?.takeIf { !isIdentifier(it) }?.let { return "has an invalid activity class" }
        l.action?.takeIf { !isIdentifier(it) }?.let { return "has an invalid action" }
        l.category?.takeIf { !isIdentifier(it) }?.let { return "has an invalid category" }
        l.flags.firstOrNull { it !in EmulatorProfileAdmission.ALLOWED_INTENT_FLAGS }
            ?.let { return "requests unsupported intent flag '$it'" }
        l.mimeType?.takeIf { !MIME_TYPE.matches(it) }?.let { return "has an invalid MIME type" }
        return extrasRefusal(l)
    }

    private fun extrasRefusal(l: EmulatorKbLaunch): String? {
        if (l.extras.size > MAX_EXTRAS_PER_KIND) return "has more than $MAX_EXTRAS_PER_KIND string extras"
        if (l.boolExtras.size > MAX_EXTRAS_PER_KIND) return "has more than $MAX_EXTRAS_PER_KIND boolean extras"
        if (l.arrayExtras.size > MAX_EXTRAS_PER_KIND) return "has more than $MAX_EXTRAS_PER_KIND array extras"
        val badKey = (l.extras.keys + l.boolExtras.keys + l.arrayExtras.keys).firstOrNull { !EXTRA_KEY.matches(it) }
        if (badKey != null) return "has an extra with an invalid key name"
        l.extras.entries.firstOrNull { !isExtraValue(it.value) }
            ?.let { return "has a string extra '${it.key}' that is neither one placeholder nor a plain literal" }
        for ((key, items) in l.arrayExtras) {
            if (items.size > MAX_ARRAY_ITEMS) return "has an array extra '$key' with more than $MAX_ARRAY_ITEMS items"
            if (items.any { !isExtraValue(it) }) {
                return "has an array extra '$key' with an item that is neither one placeholder nor a plain literal"
            }
        }
        return null
    }

    private fun platformRefusal(p: EmulatorKbPlatform, claimed: Set<String>): String? {
        if (p.id in claimed) return "has a duplicate platform id '${p.id}'"
        if (p.romExtensions.size > MAX_EXTENSIONS) return "lists more than $MAX_EXTENSIONS extensions"
        p.romExtensions.firstOrNull { !EXTENSION.matches(it) }
            ?.let { return "has an invalid extension '$it' (use 1-10 of a-z, 0-9, no dot)" }
        return null
    }

    private fun isIdentifier(s: String) = s.length <= MAX_IDENTIFIER_CHARS && DOTTED_IDENTIFIER.matches(s)

    // Exactly one placeholder, or a literal that can never be a path or URI. "file://{rom_path}" is neither.
    private fun isExtraValue(s: String) = s in PLACEHOLDERS || EXTRA_LITERAL.matches(s)

    private fun normalizeSigner(s: String) = s.replace(":", "").lowercase()
}
