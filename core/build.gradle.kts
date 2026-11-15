plugins { kotlin("multiplatform") }

kotlin {
    // Android consumes the JVM variant; common code remains free of platform APIs.
    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.framework { baseName = "TipStrokeCore"; isStatic = true }
    }
    sourceSets {
        all {
            languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
            languageSettings.optIn("kotlin.time.ExperimentalTime")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

// Preserve the repository's existing local verification command.
tasks.register("test") { dependsOn("jvmTest") }
