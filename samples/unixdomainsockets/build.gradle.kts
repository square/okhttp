plugins {
  kotlin("jvm")
}

dependencies {
  implementation(project(":okhttp"))
  implementation(project(":mockwebserver"))
  implementation(Dependencies.jnrUnixsocket)
}
