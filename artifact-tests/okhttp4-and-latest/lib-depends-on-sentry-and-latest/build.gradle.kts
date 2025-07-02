plugins {
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  implementation("io.sentry:sentry-okhttp:8.16.0")
  implementation(project(":lib-depends-on-latest"))
  testImplementation(libs.assertk)
  testImplementation(project(":classpathscanner"))
}
