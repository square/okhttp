import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  kotlin("jvm")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("binary-compatibility-validator")
}

project.applyOsgi(
  "Export-Package: okhttp3.brotli",
  "Automatic-Module-Name: okhttp3.brotli",
  "Bundle-SymbolicName: com.squareup.okhttp3.brotli"
)

dependencies {
  api(projects.okhttp)
  api(libs.brotli.dec)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(libs.conscrypt.openjdk)
  testImplementation(libs.junit)
  testImplementation(libs.kotlin.test.common)
  testImplementation(libs.kotlin.test.junit)
  testImplementation(libs.assertk)
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Empty()))
}
