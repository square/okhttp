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
  "Export-Package: okhttp3.jetty",
  "Automatic-Module-Name: okhttp3.jetty",
  "Bundle-SymbolicName: com.squareup.okhttp3.jetty"
)

dependencies {
  api(libs.squareup.okio)
  implementation(projects.okhttp)
  compileOnly(libs.findbugs.jsr305)
  compileOnly(libs.animalsniffer.annotations)

  implementation("org.eclipse.jetty.http3:http3-client:11.0.8")

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(projects.mockwebserver3Junit5)
  testImplementation(libs.junit)
  testImplementation(libs.assertj.core)
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
    "okhttp3.jetty.internal"
  )
}.let(tasks.check::dependsOn)

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGfm")))
}
