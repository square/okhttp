plugins {
  kotlin("jvm")
}
val testJavaVersion = System.getProperty("test.java.version", "21").toInt()

tasks.withType<Test> {
  useJUnitPlatform()

  val javaToolchains = project.extensions.getByType<JavaToolchainService>()
  javaLauncher.set(javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(testJavaVersion))
  })
}

dependencies {
  //noinspection UseTomlInstead
  implementation("com.squareup.okhttp3:okhttp:5.0.0")
  implementation("com.squareup.okhttp3:logging-interceptor:5.0.0")

  testImplementation(libs.junit)
  testImplementation(libs.assertk)
  testImplementation(libs.junit.vintage.engine)
}
