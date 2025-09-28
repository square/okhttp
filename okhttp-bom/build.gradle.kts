plugins {
  id("com.vanniktech.maven.publish.base") version "0.25.3"
  id("java-platform")
}

dependencies {
  constraints {
    project.rootProject.subprojects.forEach { subproject ->
      if (subproject.name != "okhttp-bom") {
        api(subproject)
      }
    }
    api("com.squareup.okhttp3:okhttp-jvm:${project.version}")
    api("com.squareup.okhttp3:okhttp-android:${project.version}")
  }
}

publishing {
  publications.create<MavenPublication>("maven") {
    from(project.components["javaPlatform"])
  }
}