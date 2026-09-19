pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "upi-offline-android"
include(":core-crypto")
include(":core-database")
include(":core-transport")
include(":core-mesh")
include(":core-bridge")
include(":app")
