plugins {
  kotlin("jvm")
}

dependencies {
  implementation(projects.okhttp)
  implementation(projects.mockwebserver)
  implementation(Dependencies.jnrUnixsocket)
}
