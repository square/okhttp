import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  kotlin("jvm") version "1.9.22"
  id("org.jetbrains.dokka") version "1.9.10"
  id("com.vanniktech.maven.publish.base") version "0.25.3"
  id("binary-compatibility-validator") version "0.13.2"
}

project.applyOsgi(
  "Fragment-Host: com.squareup.okhttp3; bundle-version=\"\${range;[==,+);\${version_cleanup;${projects.okhttp.version}}}\"",
  "Bundle-SymbolicName: com.squareup.okhttp3.urlconnection",
  "-removeheaders: Private-Package"
)

project.applyJavaModules("okhttp3.urlconnection")

dependencies {
  "friendsApi"(projects.okhttp)
  api(projects.okhttpJavaNetCookiejar)
  compileOnly(libs.animalsniffer.annotations)
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Empty()))
}