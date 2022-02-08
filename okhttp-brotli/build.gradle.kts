import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

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
  api(projects.okhttp)
  api(Dependencies.brotli)
  compileOnly(Dependencies.jsr305)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(Dependencies.conscrypt)
  testImplementation(Dependencies.junit)
  testImplementation(Dependencies.assertj)
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGfm")))
}
