dependencies {
  implementation(project(":okhttp"))
  implementation(Dependencies.jsoup)
}

tasks.compileJava {
  options.isWarnings = false
}
