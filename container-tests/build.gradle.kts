plugins {
  kotlin("jvm")
}

tasks.withType<Test> {
  useJUnitPlatform()
  onlyIf("By default not in CI") {
    System.getenv("CI") == null
      || (project.hasProperty("containerTests") && project.property("containerTests").toString().toBoolean())
  }

  val platform = System.getProperty("okhttp.platform", "loom")

  jvmArgs(
    "-Dokhttp.platform=$platform",
  )

  if (platform == "loom") {
    jvmArgs(
      "-Djdk.tracePinnedThreads=short",
    )
  }
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
