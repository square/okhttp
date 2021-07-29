import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
  id("com.github.johnrengelman.shadow")
}

tasks.withType<JavaCompile> {
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

tasks.named<ShadowJar>("shadowJar") {
  mergeServiceFiles()
}
