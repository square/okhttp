import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  kotlin("jvm")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("binary-compatibility-validator")
  id("ru.vyarus.animalsniffer")
}

project.applyOsgi(
  "Export-Package: okhttp3.tls",
  "Automatic-Module-Name: okhttp3.tls",
  "Bundle-SymbolicName: com.squareup.okhttp3.tls"
)

dependencies {
  api(libs.squareup.okio)
  implementation(projects.okhttp)
  compileOnly(libs.findbugs.jsr305)
  compileOnly(libs.animalsniffer.annotations)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(projects.mockwebserver3Junit5)
  testImplementation(libs.junit)
  testImplementation(libs.assertj.core)
}

animalsniffer {
  // InsecureExtendedTrustManager (API 24+)
  ignore = listOf("javax.net.ssl.X509ExtendedTrustManager")
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGfm")))
}
