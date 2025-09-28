plugins {
  kotlin("jvm") version "1.9.22"
}

dependencies {
  implementation(projects.okhttp)
  implementation(projects.mockwebserver)
  implementation(libs.jnr.unixsocket)
}