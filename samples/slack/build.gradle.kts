plugins {
  kotlin("jvm")
}

dependencies {
  testImplementation(projects.okhttp)
  implementation(projects.mockwebserver)
  implementation(libs.squareup.moshi)
}
