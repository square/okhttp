dependencies {
  constraints {
    project.rootProject.subprojects.forEach { subproject ->
      val artifactId = Projects.publishedArtifactId(subproject.name)
      if (artifactId != null && artifactId != "okhttp-bom") {
        api(subproject)
      }
    }
  }
}
