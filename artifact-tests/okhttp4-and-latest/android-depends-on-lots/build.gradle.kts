plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
}

dependencies {
  implementation("com.launchdarkly:launchdarkly-android-client-sdk:5.0.0")
  implementation("com.squareup.retrofit2:retrofit:3.0.0")
  implementation("com.squareup.wire:wire-grpc-client:5.3.3")
  implementation(project(":lib-depends-on-latest"))

  androidTestImplementation("androidx.test:core:1.6.1")
  androidTestImplementation("androidx.test:runner:1.6.1")
  androidTestImplementation("androidx.test:rules:1.6.1")
  androidTestImplementation(libs.assertk)
  androidTestImplementation("junit:junit:4.13.2")
  androidTestImplementation(project(":classpathscanner"))
}

android {
  compileSdk = 35
  namespace = "com.squareup.okhttp3.libdependsonldandlatest"

  defaultConfig {
    minSdk = 21
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  compileOptions {
    targetCompatibility(JavaVersion.VERSION_11)
    sourceCompatibility(JavaVersion.VERSION_11)
  }

  kotlinOptions {
    jvmTarget = JavaVersion.VERSION_11.toString()
  }
}
