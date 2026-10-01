package com.playfieldportal.feature.launcher

import android.content.ComponentName
import android.content.pm.PackageManager
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** A supported PC-game launcher app PFP can import games from. */
enum class PcLauncherType {
    WINLATOR, GAMEHUB, GAMEHUB_LITE, BANNERHUB_V6, GAMENATIVE, MANUAL;

    /** GameHub, GameHub Lite and BannerHub share one launch adapter; only their identity differs. */
    val isGameHubFamily: Boolean
        get() = this == GAMEHUB || this == GAMEHUB_LITE || this == BANNERHUB_V6
}

/**
 * The GameHub-family architecture generation, which decides the app's external launch contract
 * (docs/windows-library-refactor-plan.md section 4, manifest-verified 2026-07-16):
 * V6 routes through `com.xiaoji.egggame.DeepLinkActivity`; V5 exposes an exported
 * `com.xj.landscape.launcher.ui.gamedetail.GameDetailActivity` behind the same
 * `<pkg>.LAUNCH_GAME` action pattern.
 */
enum class GameHubGeneration { V5, V6 }

/**
 * What an installed GameHub-family package is (setup-wizard plan section 5). [Known] names the
 * brand; an [Ambiguous] install passed the component fingerprint but no rule could tell which
 * brand it is.
 */
sealed interface LauncherIdentity {
    data object NotLauncher : LauncherIdentity
    data object Ambiguous : LauncherIdentity
    data class Known(val type: PcLauncherType) : LauncherIdentity
}

/** One installed, verified PC launcher, named by its real Android label. */
data class InstalledPcLauncher(
    val packageName: String,
    val type: PcLauncherType,
    val label: String,
    /** True when the fingerprint passed but no identity rule settled the brand. */
    val ambiguous: Boolean = false,
)

/**
 * An installed app PFP cannot identify with confidence, listed under Import PC Games ▸ Unknown
 * Windows Emulators so the user can say what it is.
 */
data class UnknownLauncher(
    val packageName: String,
    val label: String,
    val signerSha256: String?,
    /** Why it is listed, e.g. "could be GameHub or BannerHub". */
    val reason: String,
    /** What the user already said it is; null while undecided. */
    val choice: LauncherChoice?,
)

data class PcLauncherDef(
    val type: PcLauncherType,
    val displayName: String,
    // Known package names for this launcher (official + common forks). GameHub-family entries
    // share the spoof pool below, so a package match alone is NOT proof of install — use
    // [PcLauncherCatalog.verifiedInstalledPackage], which fingerprints the app's components.
    val packageNames: List<String>,
)

/**
 * Curated catalog of PC launchers the Import PC Games flow recognises. PFP is a frontend for
 * these apps, never the PC runtime: imported entries launch back into the source launcher.
 */
object PcLauncherCatalog {

    // GameHub Lite and BannerHub ReVanced release variants under spoofed package names that
    // manufacturers whitelist for performance modes. CRITICAL: the spoofed names are the GENUINE
    // package names of real apps (AnTuTu, PUBG Mobile, Genshin Impact, CrossFire) — a package
    // match must be confirmed by the component fingerprint before treating the install as a
    // launcher. Launcher-owned names first so detection prefers them.
    private val GAMEHUB_FAMILY_PACKAGES = listOf(
        "gamehub.lite",                // GameHub Lite base / BannerHub Normal-GHL
        "banner.hub",                  // BannerHub Normal
        "com.xiaoji.egggame",          // upstream GameHub / BannerHub Original
        "com.antutu.ABenchMark",       // AnTuTu variant
        "com.antutu.benchmark.full",   // alt-AnTuTu variant
        "com.ludashi.aibench",         // Ludashi variant
        "com.tencent.ig",              // PUBG variant
        "com.miHoYo.GenshinImpact",    // Genshin variant (BannerHub)
        "com.tencent.tmgp.cf",         // PuBG-CrossFire variant (BannerHub)
    )

    private const val GAMEHUB_LITE_PACKAGE = "gamehub.lite"
    private const val GAMEHUB_PACKAGE = "com.xiaoji.egggame"

    // An app outside every catalog whose label or package names one of these is worth asking
    // about (plan section 6): a fork or rebrand of a Windows emulator PFP does not know yet.
    private val WINDOWS_EMULATOR_WORDS = listOf("winlator", "wine", "gamehub", "bannerhub", "box64", "mobox")

    // Lineage marker activities — the classes are constant across variant package names, so
    // their presence both proves "this is really a GameHub-family launcher" (the genuine
    // AnTuTu/PUBG/Genshin contain neither) and picks the launch contract generation.
    const val V6_DEEP_LINK_ACTIVITY = "com.xiaoji.egggame.DeepLinkActivity"
    const val V5_GAME_DETAIL_ACTIVITY = "com.xj.landscape.launcher.ui.gamedetail.GameDetailActivity"
    private const val V5_ROUTER_ACTIVITY = "com.xj.app.DeepLinkRouterActivity"

    /** SHA-256 of GameSir's release signing certificate (`CN=gamesir`, read on the Odin3 2026-10-01). */
    const val GAMESIR_SIGNER_SHA256 = "f6dc89251d2edf60c5721524d59f2dc6373825a73b1b81d031cafeeff31c9775"

    val entries: List<PcLauncherDef> = listOf(
        PcLauncherDef(PcLauncherType.BANNERHUB_V6, "BannerHub",    GAMEHUB_FAMILY_PACKAGES),
        PcLauncherDef(PcLauncherType.GAMEHUB,      "GameHub",      GAMEHUB_FAMILY_PACKAGES),
        PcLauncherDef(PcLauncherType.GAMEHUB_LITE, "GameHub Lite", GAMEHUB_FAMILY_PACKAGES),
        PcLauncherDef(
            PcLauncherType.WINLATOR, "Winlator",
            // com.winlator.vanilla is labelled "Winlator Ludashi" on device; the Obtainium pack
            // publishes the same project as com.winlator.ludashi.
            listOf("com.winlator", "com.winlator.cmod", "com.winlator.ludashi", "com.winlator.vanilla"),
        ),
        PcLauncherDef(PcLauncherType.GAMENATIVE,   "GameNative",   listOf("app.gamenative")),
    )

    // First definition claiming a package. GameHub-family packages resolve to BannerHub here;
    // display-name-only ambiguity — the family launchers share one launch adapter, so the built
    // intent is identical either way. [identify] settles which brand an install really is.
    private val byPackage: Map<String, PcLauncherDef> =
        entries.flatMap { def -> def.packageNames.map { it to def } }.toMap()

    fun forPackage(packageName: String?): PcLauncherDef? = packageName?.let { byPackage[it] }

    /** True when [packageName] is in the shared GameHub-family pool (fingerprint still required). */
    fun isGameHubFamilyPackage(packageName: String?): Boolean =
        packageName in GAMEHUB_FAMILY_PACKAGES

    // ── Component fingerprint ─────────────────────────────────────────────────

    private data class CachedFingerprint(
        val lastUpdateTime: Long,
        val generation: GameHubGeneration?,
        // Read with the fingerprint, so identity costs no extra package query per lookup.
        val signerSha256: String?,
    )

    private val fingerprints = ConcurrentHashMap<String, CachedFingerprint>()

    /**
     * The installed package's GameHub generation, or null when the package is absent or is NOT a
     * GameHub-family launcher (e.g. the genuine app whose name a variant spoofs). Cached per
     * package and refreshed when the install's `lastUpdateTime` changes.
     */
    fun gameHubGeneration(packageName: String, pm: PackageManager): GameHubGeneration? =
        fingerprint(packageName, pm)?.generation

    private fun fingerprint(packageName: String, pm: PackageManager): CachedFingerprint? {
        val info = runCatching {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull() ?: return null
        fingerprints[packageName]
            ?.takeIf { it.lastUpdateTime == info.lastUpdateTime }
            ?.let { return it }
        val generation = resolveGeneration(
            versionNameMajor = info.versionName?.substringBefore('.')?.toIntOrNull(),
            label            = applicationLabel(packageName, pm),
            hasClass         = { cls -> hasActivity(packageName, cls, pm) },
        )
        // A rotated key lists its lineage oldest first, so the current signer is the last entry.
        val signer = info.signingInfo
            ?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
            ?.lastOrNull()
            ?.toByteArray()
            ?.let(::sha256Hex)
        return CachedFingerprint(info.lastUpdateTime, generation, signer)
            .also { fingerprints[packageName] = it }
    }

    /** SHA-256 of the install's current signing certificate, lowercase hex; null when unreadable. */
    fun signerSha256(packageName: String, pm: PackageManager): String? =
        fingerprint(packageName, pm)?.signerSha256

    // Signals in order of strength (plan section 4): lineage components decide; a family-sounding
    // label plus the versionName major is the fallback for future variants that relocate their
    // classes; anything else is the spoofed-name genuine app and resolves to null.
    internal fun resolveGeneration(
        versionNameMajor: Int?,
        label: String?,
        hasClass: (String) -> Boolean,
    ): GameHubGeneration? = when {
        hasClass(V6_DEEP_LINK_ACTIVITY) -> GameHubGeneration.V6
        hasClass(V5_GAME_DETAIL_ACTIVITY) || hasClass(V5_ROUTER_ACTIVITY) -> GameHubGeneration.V5
        labelNamesFamilyLauncher(label) ->
            if ((versionNameMajor ?: 0) >= 6) GameHubGeneration.V6 else GameHubGeneration.V5
        else -> null
    }

    // ── Identity (setup-wizard plan section 5) ────────────────────────────────

    private data class IdentityEvidence(val signer: String?, val label: String, val packageName: String)

    private class IdentityRule(val type: PcLauncherType, val matches: (IdentityEvidence) -> Boolean)

    // One ordered table, so a later emulator pack can supply these rules as data.
    private val IDENTITY_RULES = listOf(
        // A repack cannot carry GameSir's signature: this is the official app.
        IdentityRule(PcLauncherType.GAMEHUB) { it.signer == GAMESIR_SIGNER_SHA256 },
        // BannerHub labels every variant "BannerHub …" (Original, Ludashi, …).
        IdentityRule(PcLauncherType.BANNERHUB_V6) { "bannerhub" in it.label },
        IdentityRule(PcLauncherType.GAMEHUB_LITE) { "lite" in it.label || it.packageName == GAMEHUB_LITE_PACKAGE },
    )

    /**
     * Which brand a GameHub-family install is. The component fingerprint ([generation]) gates
     * launcher-hood; the ordered rules then name the brand. A verified install no rule settles is
     * [LauncherIdentity.Ambiguous].
     */
    internal fun resolveIdentity(
        signerSha256: String?,
        label: String?,
        packageName: String,
        generation: GameHubGeneration?,
    ): LauncherIdentity {
        if (generation == null) return LauncherIdentity.NotLauncher
        val evidence = IdentityEvidence(signerSha256?.lowercase(), label.orEmpty().lowercase(), packageName)
        return IDENTITY_RULES.firstOrNull { it.matches(evidence) }
            ?.let { LauncherIdentity.Known(it.type) }
            ?: LauncherIdentity.Ambiguous
    }

    /** [resolveIdentity] for an installed package; launcher-exclusive packages resolve by catalog. */
    fun identify(packageName: String, pm: PackageManager): LauncherIdentity {
        if (packageName !in GAMEHUB_FAMILY_PACKAGES) {
            val def = forPackage(packageName) ?: return LauncherIdentity.NotLauncher
            return if (isInstalled(packageName, pm)) LauncherIdentity.Known(def.type) else LauncherIdentity.NotLauncher
        }
        val print = fingerprint(packageName, pm) ?: return LauncherIdentity.NotLauncher
        return resolveIdentity(print.signerSha256, applicationLabel(packageName, pm), packageName, print.generation)
    }

    /** [identify] with the user's word on top: a choice outranks every automatic rule. */
    fun identify(packageName: String, pm: PackageManager, choices: LauncherChoices): LauncherIdentity =
        resolveWithChoice(choices.choiceFor(packageName, signerSha256(packageName, pm)), identify(packageName, pm))

    internal fun resolveWithChoice(choice: LauncherChoice?, automatic: LauncherIdentity): LauncherIdentity =
        when {
            choice == null -> automatic
            choice.type == null -> LauncherIdentity.NotLauncher
            else -> LauncherIdentity.Known(choice.type)
        }

    // ── Unknown Windows Emulators (setup-wizard plan section 6) ───────────────

    /**
     * Why an installed app should be put to the user, or null when PFP already knows. Listed: a
     * GameHub-family install no identity rule settles, and an app outside the catalog named like
     * a Windows emulator. A family-labelled install whose launch classes moved needs no rule of
     * its own — [resolveGeneration]'s label fallback verifies it, so it lands in one of these.
     * Never listed: a confidently identified launcher, or a genuine app whose package a variant
     * spoofs (catalog packages are only ever asked about once verified).
     */
    internal fun unknownReason(packageName: String, label: String?, identity: LauncherIdentity): String? = when {
        identity == LauncherIdentity.Ambiguous ->
            if (packageName == GAMEHUB_PACKAGE) "could be GameHub or BannerHub" else "could be GameHub Lite or BannerHub"
        forPackage(packageName) != null -> null
        WINDOWS_EMULATOR_WORDS.any { word ->
            packageName.contains(word, ignoreCase = true) || label?.contains(word, ignoreCase = true) == true
        } -> "not in PFP's list"
        else -> null
    }

    /**
     * Every installed app [unknownReason] would ask about, with any choice already made — decided
     * rows included, so the caller can show hidden ones on request. Never lists [ownPackage].
     */
    fun unknownLaunchers(pm: PackageManager, choices: LauncherChoices, ownPackage: String): List<UnknownLauncher> =
        runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
            .asSequence()
            .map { it.packageName }
            .filter { it != ownPackage }
            .mapNotNull { pkg ->
                // Catalog packages are fingerprinted; anything else is judged by name alone, so a
                // full app scan costs no package queries beyond the label.
                val identity = if (forPackage(pkg) != null) identify(pkg, pm) else LauncherIdentity.NotLauncher
                val label = applicationLabel(pkg, pm)
                val reason = unknownReason(pkg, label, identity) ?: return@mapNotNull null
                val signer = signerSha256(pkg, pm)
                UnknownLauncher(pkg, label ?: pkg, signer, reason, choices.choiceFor(pkg, signer))
            }
            .sortedBy { it.label.lowercase() }
            .toList()

    /** The app's real Android label — every display surface names a launcher by this. */
    fun displayName(packageName: String, pm: PackageManager): String =
        applicationLabel(packageName, pm) ?: forPackage(packageName)?.displayName ?: packageName

    /**
     * Every installed launcher that passes verification, each named by its Android label.
     * Ambiguous GameHub-family installs are included and flagged: they launch through the shared
     * family adapter whatever their brand turns out to be.
     */
    fun installedLaunchers(
        pm: PackageManager,
        choices: LauncherChoices = LauncherChoices.EMPTY,
    ): List<InstalledPcLauncher> =
        (entries.flatMap { it.packageNames } + choices.chosenLauncherPackages()).distinct().mapNotNull { pkg ->
            when (val identity = identify(pkg, pm, choices)) {
                LauncherIdentity.NotLauncher -> null
                LauncherIdentity.Ambiguous ->
                    InstalledPcLauncher(pkg, PcLauncherType.GAMEHUB, displayName(pkg, pm), ambiguous = true)
                is LauncherIdentity.Known -> InstalledPcLauncher(pkg, identity.type, displayName(pkg, pm))
            }
        }

    // ── Verification ──────────────────────────────────────────────────────────

    /**
     * The first installed package that verifiably belongs to [def]. GameHub-family packages must
     * pass the component fingerprint — so the real AnTuTu/PUBG/Genshin/CrossFire never register
     * as an installed PC launcher — and [identify] then settles which brand claims the install.
     * Winlator/GameNative packages are launcher-exclusive and need no fingerprint.
     */
    fun verifiedInstalledPackage(def: PcLauncherDef, pm: PackageManager): String? =
        def.packageNames.firstOrNull { pkg -> identify(pkg, pm) == LauncherIdentity.Known(def.type) }

    /**
     * True when [packageName] is a launcher PFP trusts as a PC-game source: a catalog package
     * that, for GameHub-family names, also passes the component fingerprint. The gate for
     * shortcut routing (plan section 3) — an undecided brand never blocks it.
     */
    fun isVerifiedPcLauncher(packageName: String?, pm: PackageManager): Boolean {
        val pkg = packageName ?: return false
        if (forPackage(pkg) == null) return false
        return if (pkg in GAMEHUB_FAMILY_PACKAGES) gameHubGeneration(pkg, pm) != null else true
    }

    /** Every installed GameHub-family package whose components confirm a launcher (variants can
     *  be installed side-by-side — that's the point of the variant scheme). */
    fun installedGameHubFamilyPackages(pm: PackageManager): List<String> =
        GAMEHUB_FAMILY_PACKAGES.filter { gameHubGeneration(it, pm) != null }

    private fun labelNamesFamilyLauncher(label: String?): Boolean =
        label != null && (
            label.contains("gamehub", ignoreCase = true) ||
                label.contains("game hub", ignoreCase = true) ||
                label.contains("banner", ignoreCase = true)
            )

    private fun isInstalled(pkg: String, pm: PackageManager): Boolean =
        runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess

    private fun applicationLabel(pkg: String, pm: PackageManager): String? = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    private fun hasActivity(pkg: String, cls: String, pm: PackageManager): Boolean =
        runCatching { pm.getActivityInfo(ComponentName(pkg, cls), 0) }.isSuccess

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
