pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // JitPack ONLY for Kadb's pairing library (spake2-java). Nothing else.
        maven("https://jitpack.io") { content { includeGroup("com.github.Flyfish233") } }
    }
}
// Separate project on purpose: it shares nothing with the watch app in ../wear.
rootProject.name = "GlucoseGlanceInstaller"
include(":app")
