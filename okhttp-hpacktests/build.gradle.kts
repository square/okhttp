plugins {
  kotlin("jvm")
}

dependencies {
  testImplementation(Dependencies.okio)
  testImplementation(Dependencies.moshi)
  testImplementation(projects.okhttp)
  testImplementation(projects.okhttpTestingSupport)
  testImplementation(project(":mockwebserver"))
  testImplementation(Dependencies.junit)
  testImplementation(Dependencies.assertj)
}
