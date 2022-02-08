plugins {
  kotlin("jvm")
}

dependencies {
  testImplementation(Dependencies.okio)
  testImplementation(Dependencies.moshi)
  testImplementation(projects.okhttp)
  testImplementation(projects.okhttpTestingSupport)
  testImplementation(projects.mockwebserver)
  testImplementation(Dependencies.junit)
  testImplementation(Dependencies.assertj)
}
