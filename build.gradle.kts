// AGP 9 compiles Kotlin itself (built-in Kotlin). This pins the Kotlin Gradle
// plugin version it uses, which must match the Compose compiler plugin.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
