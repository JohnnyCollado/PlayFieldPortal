pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PlayFieldPortal"

include(":app")

// Core modules
include(":core:core-common")
include(":core:core-domain")
include(":core:core-data")
include(":core:core-ui")

// Feature modules
include(":feature:feature-xmb")
include(":feature:feature-library")
include(":feature:feature-launcher")
include(":feature:feature-artwork")
include(":feature:feature-themes")
include(":feature:feature-settings")
include(":feature:feature-appbar")
include(":feature:feature-backup")
