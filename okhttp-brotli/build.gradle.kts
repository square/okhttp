import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
  kotlin("jvm")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
}

project.applyOsgi(
  "Export-Package: okhttp3.brotli",
  "Automatic-Module-Name: okhttp3.brotli",
  "Bundle-SymbolicName: com.squareup.okhttp3.brotli"
)

dependencies {
  api(project(":okhttp"))
  api(Dependencies.brotli)
  compileOnly(Dependencies.jsr305)

  testImplementation(project(":okhttp-testing-support"))
  testImplementation(Dependencies.conscrypt)
  testImplementation(Dependencies.junit)
  testImplementation(Dependencies.assertj)
}

configure<MavenPublishBaseExtension> {
  configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGfm")))
}
