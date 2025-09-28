import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  kotlin("jvm") version "1.9.22"
  id("org.jetbrains.dokka") version "1.9.10"
  id("com.vanniktech.maven.publish.base") version "0.25.3"
  id("binary-compatibility-validator") version "0.13.2"
}

project.applyOsgi(
  "Export-Package: okhttp3.sse",
  "Bundle-SymbolicName: com.squareup.okhttp3.sse"
)

project.applyJavaModules("okhttp3.sse")

dependencies {
  api(projects.okhttp)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(projects.mockwebserver3)
  testImplementation(projects.mockwebserver3Junit5)
  testImplementation(libs.junit)
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Empty()))
}