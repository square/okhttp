import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  kotlin("jvm") version "1.9.22"
  id("org.jetbrains.dokka") version "1.9.10"
  id("com.vanniktech.maven.publish.base") version "0.25.3"
  id("binary-compatibility-validator") version "0.13.2"
}

project.applyJavaModules("mockwebserver3.junit5")

tasks {
  test {
    useJUnitPlatform()
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