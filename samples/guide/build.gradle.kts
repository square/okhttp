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
  kapt(libs.moshiCompiler)
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(14))
  }
}

tasks.compileJava {
  options.isWarnings = false
}
