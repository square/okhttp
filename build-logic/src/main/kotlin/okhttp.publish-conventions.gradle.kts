import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
  id("com.vanniktech.maven.publish.base")
}

configure<MavenPublishBaseExtension> {
  publishToMavenCentral(automaticRelease = true)
  signAllPublications()
  pom {
    name.set(project.name)
    description.set("Square’s meticulous HTTP client for Java and Kotlin.")
    url.set("https://square.github.io/okhttp/")
    licenses {
      license {
        name.set("The Apache Software License, Version 2.0")
        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
        distribution.set("repo")
      }
    }
    scm {
      connection.set("scm:git:https://github.com/square/okhttp.git")
      developerConnection.set("scm:git:ssh://git@github.com/square/okhttp.git")
      url.set("https://github.com/square/okhttp")
    }
    developers {
      developer {
        name.set("Square, Inc.")
      }
    }
  }
}
