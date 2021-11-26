plugins {
  kotlin("jvm")
}

dependencies {
  implementation(project(":mockwebserver"))
  implementation(Dependencies.moshi)
}
