import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  kotlin("jvm")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("binary-compatibility-validator")
}

project.applyOsgi(
  "Export-Package: okhttp3.java.net.cookiejar",
  "Automatic-Module-Name: okhttp3.java.net.cookiejar",
  "Bundle-SymbolicName: com.squareup.okhttp3.java.net.cookiejar"
)

dependencies {
  "friendsApi"(projects.okhttp)
  compileOnly(libs.animalsniffer.annotations)
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Empty()))
}
