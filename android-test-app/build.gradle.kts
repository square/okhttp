@file:Suppress("UnstableApiUsage")

plugins {
  id("com.android.application")
  id("kotlin-android")
}

android {
  compileSdk = 34

  namespace = "okhttp.android.testapp"

  testBuildType = "release"

  defaultConfig {
    minSdk = 21
    targetSdk = 34
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  compileOptions {
    targetCompatibility(JavaVersion.VERSION_11)
    sourceCompatibility(JavaVersion.VERSION_11)
  }

  kotlinOptions {
    jvmTarget = JavaVersion.VERSION_11.toString()
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
  buildFeatures {
    compose = true
  }
  composeOptions {
    kotlinCompilerExtensionVersion = "1.5.7"
  }
  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

dependencies {
  implementation(libs.playservices.safetynet)
  implementation(projects.okhttp)
  implementation(projects.okhttpAndroid)
  implementation(libs.androidx.activity)
  implementation(libs.lifecycle.runtime.ktx)
  implementation(libs.activity.compose)
  implementation(platform(libs.compose.bom))
  implementation(libs.ui)
  implementation(libs.ui.graphics)
  implementation(libs.ui.tooling.preview)
  implementation(libs.material3)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)

  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.assertk)
  androidTestImplementation(platform(libs.compose.bom))
  androidTestImplementation(libs.ui.test.junit4)
  debugImplementation(libs.ui.tooling)
  debugImplementation(libs.ui.test.manifest)
}
