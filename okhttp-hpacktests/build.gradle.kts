plugins {
  kotlin("jvm")
}

dependencies {
  testImplementation(libs.squareup.okio)
  testImplementation(libs.squareup.moshi)
  testImplementation(libs.squareup.moshi.kotlin)
  testImplementation(projects.okhttp)
  testImplementation(projects.okhttpTestingSupport)
  testImplementation(projects.mockwebserver)
  testImplementation(libs.junit)
}
