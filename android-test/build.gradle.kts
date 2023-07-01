@file:Suppress("UnstableApiUsage")

plugins {
  id("com.android.library")
  kotlin("android")
  id("de.mannodermaus.android-junit5")
}

val androidBuild = property("androidBuild").toString().toBoolean()

android {
  compileSdk = 33

  namespace = "okhttp.android.test"

  defaultConfig {
    minSdk = 21

    // Make sure to use the AndroidJUnitRunner (or a sub-class) in order to hook in the JUnit 5 Test Builder
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    testInstrumentationRunnerArguments += mapOf(
      "runnerBuilder" to "de.mannodermaus.junit5.AndroidJUnit5Builder",
      "notPackage" to "org.bouncycastle"
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
  implementation(projects.okhttp)
  implementation(projects.okhttpAndroid)

  androidTestImplementation(projects.okhttpTestingSupport) {
    exclude("org.openjsse", "openjsse")
    exclude("org.conscrypt", "conscrypt-openjdk-uber")
    exclude("software.amazon.cryptools", "AmazonCorrettoCryptoProvider")
  }
  androidTestImplementation(libs.bouncycastle.bcprov)
  androidTestImplementation(libs.bouncycastle.bctls)
  androidTestImplementation(libs.conscrypt.android)
  androidTestImplementation(projects.mockwebserver3Junit5)
  androidTestImplementation(projects.okhttpBrotli)
  androidTestImplementation(projects.okhttpDnsoverhttps)
  androidTestImplementation(projects.loggingInterceptor)
  androidTestImplementation(projects.okhttpSse)
  androidTestImplementation(projects.okhttpTls)
  androidTestImplementation(projects.okhttpAndroid)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.httpClient5)
  androidTestImplementation(libs.squareup.moshi)
  androidTestImplementation(libs.squareup.moshi.kotlin)
  androidTestImplementation(libs.squareup.okio.fakefilesystem)

  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.junit.jupiter.api)
  androidTestImplementation(libs.junit5android.core)
  androidTestRuntimeOnly(libs.junit5android.runner)
}
