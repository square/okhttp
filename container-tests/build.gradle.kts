plugins {
  kotlin("jvm")
}

val test by tasks.getting
test.onlyIf { System.getenv("CI") == null || property("containerTests").toString().toBoolean() }

dependencies {
  api(projects.okhttp)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(libs.junit)
  testImplementation(libs.assertk)
  testImplementation(libs.testcontainers)
  testImplementation(libs.mockserver)
  testImplementation(libs.mockserver.client)
  testImplementation(libs.testcontainers.junit5)
}
