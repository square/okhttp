import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  kotlin("jvm")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("binary-compatibility-validator")
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
  api(projects.okhttp)
  api(projects.mockwebserver3)
  api(libs.junit.jupiter.api)
  compileOnly(libs.animalsniffer.annotations)

  testRuntimeOnly(libs.junit.jupiter.engine)
  testImplementation(libs.kotlin.junit5)
  testImplementation(projects.okhttpTestingSupport)
  testImplementation(libs.assertk)
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Empty()))
}
