import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  kotlin("jvm") version "1.9.22"
  id("org.jetbrains.dokka") version "1.9.10"
  id("com.vanniktech.maven.publish.base") version "0.25.3"
  id("binary-compatibility-validator") version "0.13.2"
}

project.applyJavaModules("okhttp3.mockwebserver")

dependencies {
  "friendsApi"(projects.okhttp)
  api(projects.mockwebserver3)
  api(libs.junit)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(projects.okhttpTls)
  testImplementation(libs.kotlin.test.common)
  testImplementation(libs.kotlin.test.junit)
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Empty()))
}