plugins {
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
  implementation(project(":mockwebserver-deprecated"))
}

tasks.shadowJar {
  mergeServiceFiles()
}
