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
  implementation(libs.kotlin.gradle.plugin.api)
  implementation(libs.gradlePlugin.mrjar)
}
