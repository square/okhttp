import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  kotlin("jvm")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("binary-compatibility-validator")
}

project.applyOsgi(
  "Export-Package: okhttp3.coroutines",
  "Automatic-Module-Name: okhttp3.coroutines",
  "Bundle-SymbolicName: com.squareup.okhttp3.coroutines"
)

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
