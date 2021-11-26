plugins {
  id("java-platform")
}

dependencies {
  constraints {
    project.rootProject.subprojects.forEach { subproject ->
      val artifactId = subproject.name.publishedArtifactId()
      if (artifactId != null && artifactId != "okhttp-bom") {
        api(subproject)
      }
    }
  }
}
