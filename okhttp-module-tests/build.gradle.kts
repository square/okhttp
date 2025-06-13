import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  id("java")
  id("application")
  id("com.github.iherasymenko.jlink") version "0.7"
  id("org.gradlex.extra-java-module-info") version "1.12"
}

dependencies {
  implementation(projects.okhttp)
  implementation(projects.loggingInterceptor)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(libs.junit)
  testImplementation(libs.kotlin.test.common)
  testImplementation(libs.kotlin.test.junit)
  testImplementation(libs.assertk)

  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
}

application {
  mainClass = "okhttp3.modules.Main"
  mainModule = "okhttp3.modules"
}

extraJavaModuleInfo {
  module("org.jetbrains:annotations", "org.jetbrains.annotations") {
    exportAllPackages()
  }
  module("com.squareup.okio:okio-jvm", "okio") {
    exportAllPackages()
    requires("kotlin.stdlib")
    requires("java.logging")
  }
  module("com.squareup.okio:okio", "okio") {
    exportAllPackages()
  }
}

val testJavaVersion = System.getProperty("test.java.version", "21").toInt()

tasks.withType<Test> {
  useJUnitPlatform()
  systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")

  enabled = testJavaVersion > 8

  javaLauncher.set(javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(testJavaVersion))
  })
}

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(21))
  }
}
