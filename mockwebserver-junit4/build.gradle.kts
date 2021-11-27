import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

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

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGfm")))
}
