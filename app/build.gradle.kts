plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseKeystoreFile = providers.environmentVariable("TIPSTROKE_KEYSTORE_FILE").orNull
val releaseKeystorePassword = providers.environmentVariable("TIPSTROKE_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("TIPSTROKE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("TIPSTROKE_KEY_PASSWORD").orNull
val ciVersionCode = providers.environmentVariable("TIPSTROKE_VERSION_CODE").orNull?.toIntOrNull()
val ciVersionName = providers.environmentVariable("TIPSTROKE_VERSION_NAME").orNull
val releaseSigningValues = listOf(
    releaseKeystoreFile,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)

if (releaseSigningValues.any { it != null } && releaseSigningValues.any { it == null }) {
    throw GradleException("Release signing requires all TIPSTROKE_KEY* environment variables")
}

android {
    namespace = "dev.tipstroke.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.tipstroke.app"
        minSdk = 29
        targetSdk = 36
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: "0.1.0"
        manifestPlaceholders["appLabel"] = "TipStroke"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "TipStroke Debug"
        }
    }

    if (releaseSigningValues.all { it != null }) {
        signingConfigs {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystoreFile))
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation(project(":core")); implementation(project(":drawing-android"))
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
