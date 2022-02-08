plugins {
  kotlin("jvm")
}

dependencies {
  testImplementation(Dependencies.okio)
  testImplementation(Dependencies.moshi)
  testImplementation(projects.okhttp)
  testImplementation(project(":okhttp-testing-support"))
  testImplementation(project(":mockwebserver"))
  testImplementation(Dependencies.junit)
  testImplementation(Dependencies.assertj)
}
