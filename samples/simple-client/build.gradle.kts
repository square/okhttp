plugins {
  kotlin("jvm") version "1.9.22"
}

dependencies {
  implementation(projects.okhttp)
  implementation(libs.squareup.moshi)
}