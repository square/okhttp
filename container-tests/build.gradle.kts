plugins {
  kotlin("jvm")
}

val platform = System.getProperty("okhttp.platform", "jdk9")
val testJavaVersion = System.getProperty("test.java.version", "21").toInt()

tasks.withType<Test> {
  useJUnitPlatform()
  onlyIf("By default not in CI") {
    System.getenv("CI") == null
      || (project.hasProperty("containerTests") && project.property("containerTests").toString().toBoolean())
  }

  jvmArgs(
    "-Dokhttp.platform=$platform",
  )

  if (platform == "loom") {
    jvmArgs(
      "-Djdk.tracePinnedThreads=short",
    )
  }

  val javaToolchains = project.extensions.getByType<JavaToolchainService>()
  javaLauncher.set(javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(testJavaVersion))
  })
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
