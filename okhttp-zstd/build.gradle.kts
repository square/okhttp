import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  kotlin("jvm") version "1.9.22"
  id("org.jetbrains.dokka") version "1.9.10"
  id("com.vanniktech.maven.publish.base") version "0.25.3"
  id("binary-compatibility-validator") version "0.13.2"
}

project.applyOsgi(
  "Export-Package: okhttp3.zstd",
  "Automatic-Module-Name: okhttp3.zstd",
  "Bundle-SymbolicName: com.squareup.okhttp3.zstd"
)

dependencies {
  "friendsApi"(projects.okhttp)
  implementation(libs.zstd.kmp.okio)

  testImplementation(projects.okhttpBrotli)
  testImplementation(projects.okhttpTestingSupport)
  testImplementation(libs.kotlin.test.common)
  testImplementation(libs.kotlin.test.junit)
  testImplementation(libs.assertk)
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Empty()))
}