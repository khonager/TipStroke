plugins {
    id("com.android.application") version "8.13.0" apply false
    id("com.android.library") version "8.13.0" apply false
    kotlin("android") version "2.2.21" apply false
    kotlin("jvm") version "2.2.21" apply false
    kotlin("multiplatform") version "2.2.21" apply false
    id("org.jetbrains.compose") version "1.10.3" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}

// Resolve the SDK jars once with Gradle so parallel Robolectric workers can run offline.
if (findProject(":app") != null) {
    // Resolve each SDK separately. Gradle would otherwise select only the newest version.
    // Tests use APIs 29 and 35, and Robolectric also loads the target SDK (36).
    val robolectricSdks = listOf("10-robolectric-5803371-i7", "15-robolectric-13954326-i7", "16-robolectric-13921718-i7").map { version ->
        configurations.detachedConfiguration(
            dependencies.create("org.robolectric:android-all-instrumented:$version@jar")
        ).apply { isTransitive = false }
    }
    val sdkDirectory = layout.buildDirectory.dir("robolectric-sdks")
    val prepareSdks = tasks.register<Sync>("prepareRobolectricSdks") {
        from(robolectricSdks)
        into(sdkDirectory)
    }
    configure(listOf(project(":app"), project(":drawing-android"))) {
        tasks.withType<Test>().configureEach {
            dependsOn(prepareSdks)
            inputs.files(robolectricSdks).withPropertyName("robolectricSdks")
            systemProperty("robolectric.dependency.dir", sdkDirectory.get().asFile.absolutePath)
        }
    }
}
