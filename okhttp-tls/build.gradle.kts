import com.android.build.gradle.internal.tasks.factory.dependsOn
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import me.champeau.gradle.japicmp.JapicmpTask

plugins {
  kotlin("jvm")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("me.champeau.gradle.japicmp")
  id("ru.vyarus.animalsniffer")
}

project.applyOsgi(
  "Export-Package: okhttp3.tls",
  "Automatic-Module-Name: okhttp3.tls",
  "Bundle-SymbolicName: com.squareup.okhttp3.tls"
)

dependencies {
  api(Dependencies.okio)
  implementation(project(":okhttp"))
  compileOnly(Dependencies.jsr305)
  compileOnly(Dependencies.animalSniffer)

  testImplementation(project(":okhttp-testing-support"))
  testImplementation(project(":mockwebserver3-junit5"))
  testImplementation(Dependencies.junit)
  testImplementation(Dependencies.assertj)
}

animalsniffer {
  // InsecureExtendedTrustManager (API 24+)
  ignore = listOf("javax.net.ssl.X509ExtendedTrustManager")
}

tasks.register<JapicmpTask>("japicmp") {
  dependsOn("jar")
  oldClasspath = files(project.baselineJar())
  newClasspath = files(tasks.jar.get().archiveFile)
  isOnlyBinaryIncompatibleModified = true
  isFailOnModification = true
  txtOutputFile = file("$buildDir/reports/japi.txt")
  isIgnoreMissingClasses = true
  isIncludeSynthetic = true
  packageExcludes = listOf(
    "okhttp3.tls.internal"
  )
}.let(tasks.check::dependsOn)

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGfm")))
}
