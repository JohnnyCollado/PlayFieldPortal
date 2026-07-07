pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.android.application" || requested.id.id == "com.android.library") {
                useModule("com.android.tools.build:gradle:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Vendored Discord Social SDK aars (Git LFS). A flatDir repo lets the library module
        // resolve them as real transitive dependencies — direct files("*.aar") deps are rejected
        // in library modules and wouldn't propagate the SDK's classes/.so to the app.
        flatDir { dirs(rootDir.resolve("discord/discord-native/libs")) }
    }
}

rootProject.name = "PlayFieldPortal"

include(":app")

// Desktop companion (Compose Multiplatform Desktop — Windows/Linux/macOS)
include(":studio")

// Core modules
include(":core:theme-kit")   // pure JVM: theme parsing/conversion shared with the desktop companion
include(":core:core-common")
include(":core:core-domain")
include(":core:core-data")
include(":core:core-ui")

// Discord Social integration
include(":discord:discord-native")

// Feature modules
include(":feature:feature-social")
include(":feature:feature-xmb")
include(":feature:feature-library")
include(":feature:feature-launcher")
include(":feature:feature-artwork")
include(":feature:feature-themes")
include(":feature:feature-settings")
include(":feature:feature-appbar")
include(":feature:feature-backup")
