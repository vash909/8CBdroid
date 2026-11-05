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
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeModule("com.github.mik3y", "usb-serial-for-android") }
        }
    }
}
rootProject.name = "8CBdroid"
include(":app", ":core", ":protocol:wsjtcb")
