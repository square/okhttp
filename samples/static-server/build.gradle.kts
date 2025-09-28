plugins {
  kotlin("jvm") version "1.9.22"
  id("com.gradleup.shadow") version "8.1.1"
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