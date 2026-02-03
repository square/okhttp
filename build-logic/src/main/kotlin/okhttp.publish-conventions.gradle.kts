import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import kotlinx.validation.ApiValidationExtension

plugins {
  id("com.vanniktech.maven.publish.base")
  id("binary-compatibility-validator")
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

  if (project.name == "okhttp") {
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty()))
  } else if (plugins.hasPlugin(JavaBasePlugin::class.java)) {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty()))
  }
}

configure<ApiValidationExtension> {
  ignoredPackages += "okhttp3.logging.internal"
  ignoredPackages += "mockwebserver3.internal"
  ignoredPackages += "okhttp3.internal"
  ignoredPackages += "mockwebserver3.junit5.internal"
  ignoredPackages += "okhttp3.brotli.internal"
  ignoredPackages += "okhttp3.sse.internal"
  ignoredPackages += "okhttp3.tls.internal"
}
