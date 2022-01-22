plugins {
  kotlin("jvm")
  kotlin("kapt")
}

dependencies {
  implementation(project(":okhttp"))
  implementation(project(":mockwebserver"))
  implementation(project(":okhttp-testing-support"))
  implementation(project(":okhttp-tls"))
  implementation(Dependencies.animalSniffer)
  implementation(Dependencies.moshi)
  kapt(Dependencies.moshiCompiler)
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
    vendor.set(JvmVendorSpec.AZUL)
  }
}

tasks.compileJava {
  options.isWarnings = false
}
