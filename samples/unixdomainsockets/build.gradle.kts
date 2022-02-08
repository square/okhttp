plugins {
  kotlin("jvm")
}

dependencies {
  implementation(projects.okhttp)
  implementation(project(":mockwebserver"))
  implementation(Dependencies.jnrUnixsocket)
}
