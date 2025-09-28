plugins {
  kotlin("jvm") version "1.9.22"
  id("ru.vyarus.animalsniffer") version "1.7.0"
}

dependencies {
  api(libs.squareup.okio)
  api(libs.squareup.kotlinPoet)
  testImplementation(libs.assertk)
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.params)

  testImplementation(rootProject.libs.junit.jupiter.engine)
}

animalsniffer {
  isIgnoreFailures = true
}