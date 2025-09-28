import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  kotlin("jvm") version "1.9.22"
  id("org.jetbrains.dokka") version "1.9.10"
  id("com.vanniktech.maven.publish.base") version "0.25.3"
  id("binary-compatibility-validator") version "0.13.2"
}

project.applyOsgi(
  "Export-Package: okhttp3.java.net.cookiejar",
  "Bundle-SymbolicName: com.squareup.okhttp3.java.net.cookiejar"
)

project.applyJavaModules("okhttp3.java.net.cookiejar")

dependencies {
  "friendsApi"(projects.okhttp)
  compileOnly(libs.animalsniffer.annotations)
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Empty()))
}