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
  compileSdk = 34

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

      targetSdk = 34
    }
  }

  compileOptions {
    targetCompatibility(JavaVersion.VERSION_11)
    sourceCompatibility(JavaVersion.VERSION_11)
  }

  kotlinOptions {
    jvmTarget = JavaVersion.VERSION_11.toString()
  }

  sourceSets {
    getByName("main") {
      assets {
        srcDir("$buildDir/generated/sources/assets")
      }
    }
  }
}

val copyPublicSuffixDatabase = tasks.register<Copy>("copyPublicSuffixDatabase") {
  from(project(":okhttp").file("src/main/resources/okhttp3/internal/publicsuffix/PublicSuffixDatabase.gz"))
  into("$buildDir/generated/sources/assets/okhttp3/internal/publicsuffix")
}

dependencies {
  api(libs.squareup.okio)
  api(projects.okhttp)
  api(projects.loggingInterceptor)
  compileOnly(libs.androidx.annotation)
  compileOnly(libs.findbugs.jsr305)
  debugImplementation(libs.androidx.annotation)
  debugImplementation(libs.findbugs.jsr305)
  compileOnly(libs.animalsniffer.annotations)
  compileOnly(libs.robolectric.android)
  implementation("androidx.startup:startup-runtime:1.2.0")

  testImplementation(libs.junit)
  testImplementation(libs.junit.ktx)
  testImplementation(libs.assertk)
  testImplementation(projects.okhttpTls)
  testImplementation(libs.androidx.test.runner)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.espresso.core)
  testImplementation(libs.squareup.okio.fakefilesystem)

  androidTestImplementation(projects.okhttpTls)
  androidTestImplementation(libs.assertk)
  androidTestImplementation(projects.mockwebserver3Junit4)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
  androidTestImplementation("androidx.test:core:1.6.1")
}

mavenPublishing {
  // AGP 7.2 embeds Dokka 4, which breaks publishing. Android modules are hardcoded to generate Javadoc instead of Gfm.
  configure(com.vanniktech.maven.publish.AndroidSingleVariantLibrary(publishJavadocJar=false))
}
