import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  kotlin("jvm")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
}

project.applyOsgi(
  "Export-Package: okhttp3.dnsoverhttps",
  "Automatic-Module-Name: okhttp3.dnsoverhttps",
  "Bundle-SymbolicName: com.squareup.okhttp3.dnsoverhttps"
)

dependencies {
  api(projects.okhttp)
  compileOnly(Dependencies.jsr305)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(projects.mockwebserver)
  testImplementation(projects.mockwebserver3Junit5)
  testImplementation(Dependencies.okioFakeFileSystem)
  testImplementation(Dependencies.conscrypt)
  testImplementation(Dependencies.junit)
  testImplementation(Dependencies.assertj)
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGfm")))
}
