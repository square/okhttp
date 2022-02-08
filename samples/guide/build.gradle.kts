plugins {
  kotlin("jvm")
  kotlin("kapt")
}

dependencies {
  implementation(projects.okhttp)
  implementation(project(":mockwebserver"))
  implementation(projects.okhttpTestingSupport)
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
