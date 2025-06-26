plugins {
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  implementation(project(":lib-depends-on-4x"))
  implementation(project(":lib-depends-on-latest"))
  testImplementation(libs.assertk)
  testImplementation(project(":classpathscanner"))
}
