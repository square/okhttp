tasks.jar {
  manifest {
    attributes("Automatic-Module-Name" to "mockwebserver3")
  }
}

dependencies {
  api(project(":okhttp"))

  testImplementation(project(":okhttp-testing-support"))
  testImplementation(project(":okhttp-tls"))
  testRuntimeOnly(project(":mockwebserver-junit5"))
  testImplementation(Dependencies.junit)
  testImplementation(Dependencies.assertj)
}

afterEvaluate {
  tasks.dokka {
    outputDirectory = "$rootDir/docs/4.x"
    outputFormat = "gfm"
  }
}
