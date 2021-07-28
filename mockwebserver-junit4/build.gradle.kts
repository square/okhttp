tasks.jar {
  manifest {
    attributes("Automatic-Module-Name" to "mockwebserver3.junit4")
  }
}

dependencies {
  api(project(":mockwebserver"))
  api(Dependencies.junit)

  testImplementation(Dependencies.assertj)
}

afterEvaluate {
  tasks.dokka {
    outputDirectory = "$rootDir/docs/4.x"
    outputFormat = "gfm"
  }
}
