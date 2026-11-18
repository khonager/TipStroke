pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "TipStroke"
include(":core", ":shared-ui")
// Xcode/Codemagic builds need Java + Xcode, not an Android SDK.
if (providers.gradleProperty("tipstroke.appleOnly").orNull != "true") {
    include(":app", ":desktop", ":drawing-android")
}
