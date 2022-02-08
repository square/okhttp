plugins {
  kotlin("jvm")
}

dependencies {
  implementation(projects.mockwebserver)
  implementation(Dependencies.moshi)
}
