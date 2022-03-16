import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  kotlin("jvm")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("binary-compatibility-validator")
}

project.applyOsgi(
  "Export-Package: okhttp3.logging",
  "Automatic-Module-Name: okhttp3.logging",
  "Bundle-SymbolicName: com.squareup.okhttp3.logging"
)

dependencies {
  api(projects.okhttp)
  compileOnly(libs.findbugs.jsr305)

  testCompileOnly(libs.findbugs.jsr305)
  testImplementation(libs.junit)
  testImplementation(projects.mockwebserver3)
  testImplementation(projects.mockwebserver3Junit5)
  testImplementation(projects.okhttpTestingSupport)
  testImplementation(projects.okhttpTls)
  testImplementation(libs.assertj.core)
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGfm")))
}
