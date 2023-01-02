import com.vanniktech.maven.publish.JavadocJar

plugins {
  id("com.android.library")
  kotlin("android")
  id("de.mannodermaus.android-junit5")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("binary-compatibility-validator")
}

android {
  compileSdk = 31

  defaultConfig {
    minSdk = 21
    targetSdk = 31

    // Make sure to use the AndroidJUnitRunner (or a sub-class) in order to hook in the JUnit 5 Test Builder
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    testInstrumentationRunnerArguments += mapOf(
      "runnerBuilder" to "de.mannodermaus.junit5.AndroidJUnit5Builder",
      "notPackage" to "org.bouncycastle"
    )

    buildFeatures {
      buildConfig = false
    }
  }

  compileOptions {
    targetCompatibility(JavaVersion.VERSION_11)
    sourceCompatibility(JavaVersion.VERSION_11)
  }

  kotlinOptions {
    jvmTarget = JavaVersion.VERSION_11.toString()
  }
}

dependencies {
  api(libs.squareup.okio)
  api(projects.okhttp)
  compileOnly(libs.androidx.annotation)
  compileOnly(libs.findbugs.jsr305)
  debugImplementation(libs.androidx.annotation)
  debugImplementation(libs.findbugs.jsr305)
  compileOnly(libs.animalsniffer.annotations)
  compileOnly(libs.robolectric.android)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(projects.mockwebserver3Junit5)
  testImplementation(libs.junit)
  testImplementation(libs.assertj.core)

  androidTestImplementation(projects.okhttpTls)
  androidTestImplementation(libs.assertj.core)
  androidTestImplementation(projects.mockwebserver3Junit5)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.junit.jupiter.api)
  androidTestImplementation(libs.junit5android.core)
  androidTestRuntimeOnly(libs.junit5android.runner)
}

mavenPublishing {
  // AGP 7.2 embeds Dokka 4, which breaks publishing. Android modules are hardcoded to generate Javadoc instead of Gfm.
  configure(com.vanniktech.maven.publish.AndroidSingleVariantLibrary(publishJavadocJar=false))
}
