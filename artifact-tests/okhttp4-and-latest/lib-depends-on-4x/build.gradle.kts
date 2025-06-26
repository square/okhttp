plugins {
  alias(libs.plugins.kotlin.jvm)
//  `java-library`
}

dependencies {
  api("com.squareup.okhttp3:okhttp:4.12.0") // This does not depend on okhttp-bom.
  testImplementation(libs.assertk)
  testImplementation(project(":classpathscanner"))
}
