plugins {
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  implementation("com.launchdarkly:launchdarkly-java-server-sdk:7.0.0")
  implementation(project(":lib-depends-on-latest"))
  testImplementation(libs.assertk)
  testImplementation(project(":classpathscanner"))
}
