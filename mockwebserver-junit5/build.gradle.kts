import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  kotlin("jvm")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
}

tasks {
  jar {
    manifest {
      attributes("Automatic-Module-Name" to "mockwebserver3.junit5")
    }
  }
  test {
    useJUnitPlatform()
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
  }
}

dependencies {
  api(project(":mockwebserver3"))
  api(Dependencies.junit5Api)
  compileOnly(Dependencies.animalSniffer)

  testRuntimeOnly(Dependencies.junit5JupiterEngine)
  testImplementation(Dependencies.assertj)
  testImplementation(Dependencies.kotlinJunit5)
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGfm")))
}
