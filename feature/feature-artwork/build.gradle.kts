plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

import java.security.MessageDigest
import java.util.Properties

// ── ScreenScraper developer-pair obfuscation (build-time) ─────────────────────
// Loads screenscraper.devId / screenscraper.devPassword (or legacy SS_DEV_ID /
// SS_DEV_PASSWORD, or the SS_DEV_* environment variables for CI) plus the optional
// screenscraper.obfuscationSalt from local.properties and XOR-encodes each value with
// SHA-256(salt + propertyName) as the keystream. Encoded as buildConfigField byte arrays;
// credentials/DevPairDecoder reassembles them at runtime. DevPairDecoderTest mirrors this
// derivation exactly — change both together or the tests will catch the drift.
// Precedence: modern prop name → legacy prop name → environment. Absent credentials
// compile to empty arrays, which the decoder turns into null — the fallback simply disables.
private val ssProps: Properties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

private fun ssEncoded(prop: String, envName: String): Pair<String, String> {
    val value = ssProps.getProperty(prop)
        ?: ssProps.getProperty(envName)
        ?: System.getenv(envName)
        ?: ""
    if (value.isEmpty()) return "new byte[]{}" to "new byte[]{}"
    val salt = (ssProps.getProperty("screenscraper.obfuscationSalt")
        ?: "playfieldportal-default-salt").toByteArray(Charsets.UTF_8)
    val key = MessageDigest.getInstance("SHA-256")
        .digest(salt + prop.toByteArray(Charsets.UTF_8))
    val plain = value.toByteArray(Charsets.UTF_8)
    val share = ByteArray(plain.size) { i ->
        (plain[i].toInt() xor key[i % key.size].toInt()).toByte()
    }
    val mask = ByteArray(plain.size) { i -> key[i % key.size] }
    fun bytesLiteral(b: ByteArray) = "new byte[]{" + b.joinToString(",") { it.toString() } + "}"
    return bytesLiteral(share) to bytesLiteral(mask)
}

android {
    namespace  = "com.playfieldportal.feature.artwork"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        buildConfigField("String", "SS_SOFT_NAME", "\"PlayFieldPortal\"")

        // ScreenScraper developer pair (devid/devpassword), obfuscated into the APK.
        //
        // The WebAPI refuses every call without this pair, so it has to ride along. History:
        // these used to be plain buildConfigField strings from local.properties, which shipped a
        // live credential recoverable from the binary with `strings` (R8 does not touch string
        // literals). They were then moved to per-user entry under Settings ▸ Artwork, which left
        // every user unable to configure the provider at all. The current compromise ships the
        // pair XOR-encoded with a key derived from `screenscraper.obfuscationSalt` in
        // local.properties, split across four buildConfigField byte arrays; DevPairDecoder
        // reassembles them at runtime. There is no user-entered override — this is the only dev
        // pair the app has.
        //
        // This is obfuscation, not security: dex2jar + a decompiler recovers it. It defeats
        // `strings` scrapes and automated harvesters only. Rotation = change the values (and
        // ideally the salt) in local.properties and release; no server round-trip.
        val (ssDevIdShare, ssDevIdMask)           = ssEncoded("screenscraper.devId", "SS_DEV_ID")
        val (ssDevPwShare, ssDevPwMask)           = ssEncoded("screenscraper.devPassword", "SS_DEV_PASSWORD")
        buildConfigField("byte[]", "SS_DEV_ID_SHARE",       ssDevIdShare)
        buildConfigField("byte[]", "SS_DEV_ID_MASK",        ssDevIdMask)
        buildConfigField("byte[]", "SS_DEV_PASSWORD_SHARE", ssDevPwShare)
        buildConfigField("byte[]", "SS_DEV_PASSWORD_MASK",  ssDevPwMask)
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }

    testOptions {
        // Robolectric 4.16 emulates up to SDK 36. Library modules default targetSdk to
        // compileSdk (37), which Robolectric rejects outright, so pin the test target here.
        // This affects unit tests only — the published library is unchanged.
        targetSdk = 36
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.bundles.lifecycle)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.ktor)
    // api, not implementation: ArtworkImageCache exposes coil3.ImageLoader in its constructor,
    // so :app needs the type on its compile classpath for Hilt to construct it.
    api(libs.coil.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.workmanager.ktx)
    // Local ICON1 snap generation: trim + downscale full videos when SS has no normalized snap.
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.common)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.work.compiler)
    testImplementation(libs.bundles.test.unit)
    // ArtworkImageCacheTest drives a real Coil ImageLoader, which needs an Android Context.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    implementation(project(":core:core-common"))
    implementation(project(":core:core-domain"))
    implementation(project(":core:core-data"))
    implementation(project(":core:core-ui"))
}
