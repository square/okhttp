plugins {
  `kotlin-dsl`
  id("com.diffplug.spotless") version "8.4.0"
  id("com.android.lint") version "1.0.0-alpha05"
}

configure<com.diffplug.gradle.spotless.SpotlessExtension> {
  kotlin {
    target("src/**/*.kt")
    ktlint()
  }
  kotlinGradle {
    target("*.kts")
    targetExclude("build/**/*.kts")
    ktlint()
  }
}

repositories {
  google()
  mavenCentral()
  gradlePluginPortal()
}

dependencies {
  implementation(libs.gradlePlugin.kotlin)
  implementation(libs.gradlePlugin.mavenPublish)
  implementation(libs.gradlePlugin.dokka)
  implementation(libs.gradlePlugin.binaryCompatibilityValidator)
  implementation(libs.gradlePlugin.android)
  implementation(libs.gradlePlugin.bnd)
  implementation(libs.aqute.bnd)
  implementation(libs.gradlePlugin.animalsniffer)
  implementation(libs.gradlePlugin.spotless)
  implementation(libs.gradlePlugin.shadow)
  implementation(libs.gradlePlugin.graalvm)
  implementation(libs.gradlePlugin.ksp)
  implementation(libs.gradlePlugin.mrjar)
  implementation(libs.gradlePlugin.tapmoc)
  implementation(libs.kotlin.gradle.plugin.api)
  lintChecks(libs.androidx.lint.gradle)
}
