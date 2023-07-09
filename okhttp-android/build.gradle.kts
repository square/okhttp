@file:SuppressLint("OldTargetApi")

import android.annotation.SuppressLint
import com.vanniktech.maven.publish.JavadocJar

plugins {
  id("com.android.library")
  kotlin("android")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("binary-compatibility-validator")
}

android {
  compileSdk = 33

  namespace = "okhttp.android"

  defaultConfig {
    minSdk = 21

    // Make sure to use the AndroidJUnitRunner (or a sub-class) in order to hook in the JUnit 5 Test Builder
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    buildFeatures {
      buildConfig = false
    }

    testOptions {
      unitTests {
        isIncludeAndroidResources = true
      }
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

  testImplementation(libs.junit)
  testImplementation(libs.junit.ktx)
  testImplementation(libs.assertj.core)
  testImplementation(projects.okhttpTls)
  testImplementation(libs.androidx.test.runner)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.espresso.core)
  testImplementation(libs.squareup.okio.fakefilesystem)

  androidTestImplementation(projects.okhttpTls)
  androidTestImplementation(libs.assertj.core)
  androidTestImplementation(projects.mockwebserver3Junit4)
  androidTestImplementation(libs.androidx.test.runner)
}

mavenPublishing {
  // AGP 7.2 embeds Dokka 4, which breaks publishing. Android modules are hardcoded to generate Javadoc instead of Gfm.
  configure(com.vanniktech.maven.publish.AndroidSingleVariantLibrary(publishJavadocJar=false))
}
