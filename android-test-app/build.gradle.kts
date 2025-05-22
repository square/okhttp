@file:Suppress("UnstableApiUsage")

plugins {
  id("com.android.application")
  id("kotlin-android")
}

android {
  compileSdk = 35

  namespace = "okhttp.android.testapp"

  testBuildType = "release"

  defaultConfig {
    minSdk = 21
    targetSdk = 35
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  compileOptions {
    targetCompatibility(JavaVersion.VERSION_17)
    sourceCompatibility(JavaVersion.VERSION_17)
  }

  kotlinOptions {
    jvmTarget = JavaVersion.VERSION_17.toString()
  }

  buildTypes {
    release {
      isShrinkResources = true
      isMinifyEnabled = true
      signingConfig = signingConfigs.getByName("debug")
      setProguardFiles(listOf(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro"))
      testProguardFiles("test-proguard-rules.pro")
    }
  }
}

dependencies {
  implementation(libs.playservices.safetynet)
  implementation(projects.okhttp)
  implementation(libs.androidx.activity)

  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.assertk)
}
