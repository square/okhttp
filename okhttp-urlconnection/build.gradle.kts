import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  kotlin("jvm")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("binary-compatibility-validator")
}

project.applyOsgi(
  "Fragment-Host: com.squareup.okhttp3; bundle-version=\"\${range;[==,+);\${version_cleanup;${projects.okhttp.version}}}\"",
  "Automatic-Module-Name: okhttp3.urlconnection",
  "Bundle-SymbolicName: com.squareup.okhttp3.urlconnection",
  "-removeheaders: Private-Package"
)

dependencies {
  api(projects.okhttp)
  compileOnly(libs.findbugs.jsr305)
  compileOnly(libs.animalsniffer.annotations)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(projects.okhttpTls)
  testImplementation(projects.mockwebserver)
  testImplementation(libs.junit)
  testImplementation(libs.assertj.core)
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGfm")))
}
