import com.android.build.gradle.internal.tasks.factory.dependsOn
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import me.champeau.gradle.japicmp.JapicmpTask

plugins {
  kotlin("jvm")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("binary-compatibility-validator")
  id("me.champeau.gradle.japicmp")
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

tasks.register<JapicmpTask>("japicmp") {
  dependsOn("jar")
  oldClasspath = files(project.baselineJar())
  newClasspath = files(tasks.jar.get().archiveFile)
  isOnlyBinaryIncompatibleModified = true
  isFailOnModification = true
  txtOutputFile = file("$buildDir/reports/japi.txt")
  isIgnoreMissingClasses = true
  isIncludeSynthetic = true
}.let(tasks.check::dependsOn)

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGfm")))
}
