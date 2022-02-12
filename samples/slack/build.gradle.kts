plugins {
  kotlin("jvm")
}

dependencies {
  implementation(projects.mockwebserver)
  implementation(libs.squareup.moshi)
}
