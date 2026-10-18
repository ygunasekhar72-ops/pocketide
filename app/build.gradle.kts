import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Pinned debug signing: CI decodes DEBUG_KEYSTORE_B64 to a file and exports
// DEBUG_KEYSTORE_FILE, so every CI APK is signed by the same long-lived key and
// installs as an in-place update over previous ones. Without the env (local
// builds, fork PRs without secret access) AGP's default debug keystore is used.
val pinnedDebugKeystore: File? = System.getenv("DEBUG_KEYSTORE_FILE")
    ?.takeIf { file(it).isFile && file(it).length() > 0L }

android {
    namespace = "com.pocketide.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pocketide.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.0-M0.5"
    }

    signingConfigs {
        if (pinnedDebugKeystore != null) {
            create("pinnedDebug") {
                storeFile = pinnedDebugKeystore
                storePassword = System.getenv("DEBUG_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DEBUG_KEYSTORE_ALIAS")
                keyPassword = System.getenv("DEBUG_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug {
            if (pinnedDebugKeystore != null) {
                signingConfig = signingConfigs.getByName("pinnedDebug")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                test.testLogging {
                    events("failed", "skipped")
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                    showCauses = true
                    showStackTraces = true
                }
            }
        }
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // debugImplementation (per androidx docs): ui-test-manifest is an empty
    // module whose manifest declares androidx.activity.ComponentActivity so
    // createComposeRule() can launch it. On testImplementation its manifest is
    // never merged into the debug manifest Robolectric's unit-test config
    // consumes, producing "Unable to resolve activity ... ComponentActivity".
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
