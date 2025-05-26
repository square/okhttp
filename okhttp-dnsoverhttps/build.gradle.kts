import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  kotlin("jvm")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("binary-compatibility-validator")
}

project.applyOsgi(
  "Export-Package: okhttp3.dnsoverhttps",
  "Automatic-Module-Name: okhttp3.dnsoverhttps",
  "Bundle-SymbolicName: com.squareup.okhttp3.dnsoverhttps"
)

dependencies {
  "friendsApi"(projects.okhttp)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(projects.mockwebserver)
  testImplementation(projects.mockwebserver3Junit5)
  testImplementation(libs.squareup.okio.fakefilesystem)
  testImplementation(libs.conscrypt.openjdk)
  testImplementation(libs.junit)
  testImplementation(libs.kotlin.test.common)
  testImplementation(libs.kotlin.test.junit)
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Empty()))
}
