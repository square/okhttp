import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  kotlin("jvm") version "1.9.22"
  id("org.jetbrains.dokka") version "1.9.10"
  id("com.vanniktech.maven.publish.base") version "0.25.3"
  id("binary-compatibility-validator") version "0.13.2"
}

project.applyOsgi(
  "Export-Package: okhttp3.coroutines",
  "Bundle-SymbolicName: com.squareup.okhttp3.coroutines"
)

project.applyJavaModules("okhttp3.coroutines")

dependencies {
  api(projects.okhttp)
  implementation(libs.kotlinx.coroutines.core)
  api(libs.squareup.okio)
  api(libs.kotlin.stdlib)

  testImplementation(libs.kotlin.test.annotations)
  testImplementation(libs.kotlin.test.common)
  testImplementation(libs.kotlin.test.junit)
  testApi(libs.assertk)
  testImplementation(projects.okhttpTestingSupport)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(projects.mockwebserver3Junit5)
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Empty()))
}