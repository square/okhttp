plugins {
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  api("com.squareup.okhttp3:okhttp-jvm:5.0.0-SNAPSHOT") // This does not depend on okhttp-bom.
  testImplementation(libs.assertk)
  testImplementation(project(":classpathscanner"))
}
