plugins {
  kotlin("jvm")
}

dependencies {
  implementation(project(":mockwebserver-deprecated"))
  implementation(Dependencies.moshi)
}
