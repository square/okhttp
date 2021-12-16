plugins {
  kotlin("jvm")
  application
}

application {
  mainClass.set("okhttp3.sample.Crawler")
}

dependencies {
  implementation(project(":okhttp"))
  implementation(Dependencies.jsoup)
}

tasks.compileJava {
  options.isWarnings = false
}
