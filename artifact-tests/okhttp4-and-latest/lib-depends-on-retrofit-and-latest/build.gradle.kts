plugins {
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  implementation("com.squareup.retrofit2:retrofit:3.0.0")
  implementation(project(":lib-depends-on-latest"))
  testImplementation(libs.assertk)
  testImplementation(project(":classpathscanner"))
}
