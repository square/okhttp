import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
  kotlin("jvm")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
}

tasks.jar {
  manifest {
    attributes("Automatic-Module-Name" to "mockwebserver3.junit4")
  }
}

dependencies {
  api(project(":mockwebserver3"))
  api(Dependencies.junit)

  testImplementation(Dependencies.assertj)
}

configure<MavenPublishBaseExtension> {
  configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGfm")))
}
