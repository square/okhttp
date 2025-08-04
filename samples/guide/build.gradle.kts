plugins {
  kotlin("jvm")
  id("com.google.devtools.ksp")
}

dependencies {
  "friendsImplementation"(projects.okhttp)
  implementation(projects.mockwebserver)
  implementation(projects.okhttpTestingSupport)
  implementation(projects.okhttpTls)
  implementation(libs.animalsniffer.annotations)
  implementation(libs.squareup.moshi)
  implementation(libs.squareup.okio.fakefilesystem)
  ksp(libs.squareup.moshi.compiler)
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}

tasks.compileJava {
  options.isWarnings = false
}
