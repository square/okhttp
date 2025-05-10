@file:Suppress("UnstableApiUsage")

plugins {
  id("com.android.library")
  kotlin("android")
  id("de.mannodermaus.android-junit5")
}

val androidBuild = property("androidBuild").toString().toBoolean()

android {
  compileSdk = 35

  namespace = "okhttp.android.test"

  defaultConfig {
    minSdk = 21

    // Make sure to use the AndroidJUnitRunner (or a sub-class) in order to hook in the JUnit 5 Test Builder
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    testInstrumentationRunnerArguments += mapOf(
      "runnerBuilder" to "de.mannodermaus.junit5.AndroidJUnit5Builder",
      "notPackage" to "org.bouncycastle",
      "configurationParameters" to "junit.jupiter.extensions.autodetection.enabled=true"
    )
  }

  if (androidBuild) {
    sourceSets["androidTest"].java.srcDirs(
      "../okhttp-brotli/src/test/java",
      "../okhttp-dnsoverhttps/src/test/java",
      "../okhttp-logging-interceptor/src/test/java",
      "../okhttp-sse/src/test/java"
    )
  }

  compileOptions {
    targetCompatibility(JavaVersion.VERSION_11)
    sourceCompatibility(JavaVersion.VERSION_11)
  }

  testOptions {
    targetSdk = 34
  }

  kotlinOptions {
    jvmTarget = JavaVersion.VERSION_11.toString()
  }

  // issue merging due to conflict with httpclient and something else
  packagingOptions.resources.excludes += setOf(
    "META-INF/DEPENDENCIES",
    "META-INF/LICENSE.md",
    "META-INF/LICENSE-notice.md",
    "README.txt",
    "org/bouncycastle/LICENSE"
  )
}

dependencies {
  implementation(libs.kotlin.reflect)
  implementation(libs.playservices.safetynet)
  "friendsImplementation"(projects.okhttp)
  "friendsImplementation"(projects.okhttpDnsoverhttps)

  testImplementation(projects.okhttp)
  testImplementation(libs.junit)
  testImplementation(libs.junit.ktx)
  testImplementation(libs.assertk)
  testImplementation(projects.okhttpTls)
  "friendsTestImplementation"(projects.loggingInterceptor)
  testImplementation(libs.androidx.test.runner)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.espresso.core)
  testImplementation(libs.squareup.okio.fakefilesystem)
  testImplementation(projects.okhttpTestingSupport)
  testImplementation(rootProject.libs.conscrypt.openjdk)
  testImplementation(rootProject.libs.junit.jupiter.engine)
  testImplementation(rootProject.libs.junit.vintage.engine)

  androidTestImplementation(projects.okhttpTestingSupport) {
    exclude("org.openjsse", "openjsse")
    exclude("org.conscrypt", "conscrypt-openjdk-uber")
    exclude("software.amazon.cryptools", "AmazonCorrettoCryptoProvider")
  }
  androidTestImplementation(libs.assertk)
  androidTestImplementation(libs.bouncycastle.bcprov)
  androidTestImplementation(libs.bouncycastle.bctls)
  androidTestImplementation(libs.conscrypt.android)
  androidTestImplementation(projects.mockwebserver3Junit4)
  androidTestImplementation(projects.mockwebserver3Junit5)
  androidTestImplementation(projects.okhttpBrotli)
  androidTestImplementation(projects.okhttpDnsoverhttps)
  androidTestImplementation(projects.loggingInterceptor)
  androidTestImplementation(projects.okhttpSse)
  androidTestImplementation(projects.okhttpTls)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.httpClient5)
  androidTestImplementation(libs.kotlin.test.common)
  androidTestImplementation(libs.kotlin.test.junit)
  androidTestImplementation(libs.squareup.moshi)
  androidTestImplementation(libs.squareup.moshi.kotlin)
  androidTestImplementation(libs.squareup.okio.fakefilesystem)

  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.junit.jupiter.api)
  androidTestImplementation(libs.junit5android.core)
  androidTestRuntimeOnly(libs.junit5android.runner)
}
