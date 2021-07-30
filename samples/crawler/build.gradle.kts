dependencies {
  implementation(project(":okhttp"))
  implementation(Dependencies.jsoup)
}

tasks.withType<JavaCompile> {
  options.isWarnings = false
}
