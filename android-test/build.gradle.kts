plugins {
  id("com.android.library")
  kotlin("android")
  id("de.mannodermaus.android-junit5")
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
  }

  sourceSets["androidTest"].java.srcDirs(
    "../okhttp-brotli/src/test/java",
    "../okhttp-dnsoverhttps/src/test/java",
    "../okhttp-logging-interceptor/src/test/java",
    "../okhttp-sse/src/test/java"
  )

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
  implementation(Dependencies.kotlinReflect)
  implementation(Dependencies.playServicesSafetynet)
  implementation(project(":okhttp"))

  androidTestImplementation(project(":okhttp-testing-support")) {
    exclude("org.openjsse", "openjsse")
    exclude("org.conscrypt", "conscrypt-openjdk-uber")
    exclude("software.amazon.cryptools", "AmazonCorrettoCryptoProvider")
  }
  androidTestImplementation(Dependencies.bouncycastle)
  androidTestImplementation(Dependencies.bouncycastletls)
  androidTestImplementation(Dependencies.conscryptAndroid)
  androidTestImplementation(project(":mockwebserver-junit5"))
  androidTestImplementation(project(":okhttp-brotli"))
  androidTestImplementation(project(":okhttp-dnsoverhttps"))
  androidTestImplementation(project(":okhttp-logging-interceptor"))
  androidTestImplementation(project(":okhttp-sse"))
  androidTestImplementation(project(":okhttp-testing-support"))
  androidTestImplementation(project(":okhttp-tls"))
  androidTestImplementation(Dependencies.androidxExtJunit)
  androidTestImplementation(Dependencies.androidxEspressoCore)
  androidTestImplementation(Dependencies.httpclient5)
  androidTestImplementation(Dependencies.moshi)
  androidTestImplementation(Dependencies.moshiKotlin)
  androidTestImplementation(Dependencies.okioFakeFileSystem)

  androidTestImplementation(Dependencies.androidxTestRunner)
  androidTestImplementation(Dependencies.junit5Api)
  androidTestImplementation(Dependencies.junit5AndroidCore)
  androidTestRuntimeOnly(Dependencies.junit5AndroidRunner)
}
