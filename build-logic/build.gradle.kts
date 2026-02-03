plugins {
  `kotlin-dsl`
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
  implementation(libs.gradlePlugin.animalsniffer)
  implementation(libs.gradlePlugin.spotless)
  implementation(libs.gradlePlugin.shadow)
  implementation(libs.gradlePlugin.graalvm)
  implementation(libs.gradlePlugin.ksp)
  implementation(libs.gradlePlugin.mrjar)
  implementation(libs.androidx.lint.gradle)
  implementation(libs.kotlin.gradle.plugin.api)
}
