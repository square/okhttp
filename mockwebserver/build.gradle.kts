import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  kotlin("jvm")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("binary-compatibility-validator")
}

tasks.jar {
  manifest {
    attributes("Automatic-Module-Name" to "mockwebserver3")
  }
}

dependencies {
  "friendsApi"(projects.okhttp)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(projects.okhttpTls)
  testRuntimeOnly(projects.mockwebserver3Junit5)
  testImplementation(libs.junit)
  testImplementation(libs.kotlin.test.common)
  testImplementation(libs.kotlin.test.junit)
  testImplementation(libs.assertk)
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Empty()))
}
