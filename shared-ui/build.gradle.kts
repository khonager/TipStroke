plugins {
    kotlin("multiplatform")
    id("com.android.library") apply false
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appleOnly = providers.gradleProperty("tipstroke.appleOnly").orNull == "true"
if (!appleOnly) apply(plugin = "com.android.library")

kotlin {
    if (!appleOnly) androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "TipStrokeShared"
            isStatic = true
            export(project(":core"))
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation("org.jetbrains.compose.material3:material3:1.9.0")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

if (!appleOnly) extensions.configure<com.android.build.gradle.LibraryExtension> {
    namespace = "dev.tipstroke.ui"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// AGP registers this aggregate after project evaluation.
tasks.matching { it.name == "test" }.configureEach { dependsOn("jvmTest") }
