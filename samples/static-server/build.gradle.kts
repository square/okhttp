plugins {
  kotlin("jvm")
  id("com.gradleup.shadow")
}

tasks.compileJava {
  options.isWarnings = false
}

tasks.jar {
  manifest {
    attributes("Main-Class" to "okhttp3.sample.SampleServer")
  }
}

dependencies {
  implementation(projects.okhttp)
  implementation(projects.mockwebserver)
}

tasks.shadowJar {
  mergeServiceFiles()
}
