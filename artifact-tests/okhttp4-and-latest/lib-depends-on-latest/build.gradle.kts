plugins {
  alias(libs.plugins.kotlin.jvm)
//  `java-library`
}

dependencies {
  api("com.squareup.okhttp3:okhttp:5.0.0-SNAPSHOT") // This does not depend on okhttp-bom.
  testImplementation(libs.assertk)
  testImplementation(project(":classpathscanner"))
}
