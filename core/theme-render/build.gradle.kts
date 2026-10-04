// theme-render: the theme's colour rules — contrast, text roles, the storefront and detail
// palettes and the shared visual tokens — as pure Kotlin over Compose's Color. Both the launcher
// (through core-ui) and the desktop Theme Studio depend on it, so the Studio's preview runs the
// launcher's own rules instead of a copy. No Android and no composables here: CompositionLocals
// and @Composable wrappers stay in core-ui.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // api: Color is in every signature here.
    api(libs.compose.mp.ui.graphics)
    // @Immutable only; every consumer already carries the Compose runtime.
    compileOnly(libs.compose.mp.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    // TextLegibilityTest pins its bands against the cascade's own gradient anchors.
    testImplementation(project(":core:theme-kit"))
}
