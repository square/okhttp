plugins {
  id("com.vanniktech.maven.publish.base")
  id("java-platform")
}

dependencies {
  constraints {
    project.rootProject.subprojects.forEach { subproject ->
      if (subproject.name != "okhttp-bom") {
        api(subproject)
      }
    }
  }
}

publishing {
  publications.create<MavenPublication>("maven") {
    from(project.components["javaPlatform"])
  }
}
