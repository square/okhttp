plugins {
  kotlin("jvm")
  kotlin("kapt")
}

dependencies {
  implementation(projects.okhttp)
  implementation(projects.mockwebserver)
  implementation(projects.okhttpTestingSupport)
  implementation(projects.okhttpTls)
  implementation(libs.animalsniffer.annotations)
  implementation(libs.squareup.moshi)
  implementation(libs.squareup.okio.fakefilesystem)
  kapt(libs.squareup.moshi.compiler)

  implementation("org.testcontainers:testcontainers:1.17.6")
  implementation("com.github.mike10004:fengyouchao-sockslib:1.0.6")
  implementation("org.slf4j:slf4j-jdk14:2.0.5")
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}

tasks.compileJava {
  options.isWarnings = false
}
