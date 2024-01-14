plugins {
  kotlin("jvm")
}

tasks.withType<Test> {
  useJUnitPlatform()
  onlyIf { System.getenv("CI") == null || property("containerTests").toString().toBoolean() }
}

dependencies {
  api(projects.okhttp)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.params)
  testImplementation(libs.junit.jupiter.engine)
  testImplementation(libs.assertk)
  testImplementation(libs.testcontainers)
  testImplementation(libs.mockserver)
  testImplementation(libs.mockserver.client)
  testImplementation(libs.testcontainers.junit5)
}
