plugins {
  kotlin("jvm")
  id("com.github.johnrengelman.shadow")
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
  implementation(projects.mockwebserver)
}

tasks.shadowJar {
  mergeServiceFiles()
}
