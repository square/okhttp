@file:Suppress("UnstableApiUsage")

plugins {
  id("com.android.application")
}

android {
  compileSdk = 36

  namespace = "okhttp.android.testapp"

  testBuildType = "release"

  defaultConfig {
    minSdk = 21
    targetSdk = 36
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  compileOptions {
    targetCompatibility(JavaVersion.VERSION_11)
    sourceCompatibility(JavaVersion.VERSION_11)
  }


  buildTypes {
    release {
      isShrinkResources = true
      isMinifyEnabled = true
      signingConfig = signingConfigs.getByName("debug")
      setProguardFiles(listOf(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"))
      testProguardFiles("test-proguard-rules.pro")
    }
  }

  lint {
    abortOnError = true
  }
}

dependencies {
  implementation(libs.playservices.safetynet)
  implementation(projects.okhttp)
  implementation(libs.androidx.activity)
  implementation(libs.androidx.tracing)

  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.assertk)
  androidTestImplementation(libs.androidx.tracing)
}
