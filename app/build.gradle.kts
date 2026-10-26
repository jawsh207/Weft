import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.weft.keyboard"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.weft.keyboard"
        minSdk = 36 // Android 16+
        targetSdk = 36
        // CI passes the Actions run number and the release tag, so every build
        // installs as an update over the one before it.
        versionCode = System.getenv("WEFT_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("WEFT_VERSION_NAME")?.removePrefix("v") ?: "0.1.0-dev"
    }

    // Release signing comes from the environment (GitHub secrets in CI; see README › Signing).
    val keystorePath = System.getenv("WEFT_KEYSTORE_FILE")
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("WEFT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("WEFT_KEY_ALIAS")
                keyPassword = System.getenv("WEFT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // R8: shrink, optimize and obfuscate; drop unused resources.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Dictionaries are memory-mapped straight from the APK, so keep them uncompressed.
    androidResources {
        noCompress += "ekd"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // F-Droid: don't embed the encrypted Google dependency metadata blob.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.savedstate)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
