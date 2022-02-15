plugins {
  kotlin("jvm")
  application
}

application {
  mainClass.set("okhttp3.sample.Crawler")
}

dependencies {
  implementation(projects.okhttp)
  implementation(libs.jsoup)
}

tasks.compileJava {
  options.isWarnings = false
}
