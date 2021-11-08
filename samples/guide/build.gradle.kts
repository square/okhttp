plugins {
  kotlin("kapt")
}

dependencies {
  implementation(project(":okhttp"))
  implementation(project(":mockwebserver-deprecated"))
  implementation(project(":okhttp-testing-support"))
  implementation(project(":okhttp-tls"))
  implementation(Dependencies.animalSniffer)
  implementation(Dependencies.moshi)
  kapt(Dependencies.moshiCompiler)
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(14))
  }
}

tasks.compileJava {
  options.isWarnings = false
}
