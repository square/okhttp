import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  kotlin("jvm")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("binary-compatibility-validator")
}

project.applyOsgi(
  "Export-Package: okhttp3.sse",
  "Automatic-Module-Name: okhttp3.sse",
  "Bundle-SymbolicName: com.squareup.okhttp3.sse"
)

dependencies {
  api(projects.okhttp)
  compileOnly(libs.findbugs.jsr305)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(projects.mockwebserver3)
  testImplementation(projects.mockwebserver3Junit5)
  testImplementation(libs.junit)
  testImplementation(libs.assertj.core)
  testCompileOnly(libs.findbugs.jsr305)
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGfm")))
}
