import com.android.build.gradle.internal.tasks.factory.dependsOn
import me.champeau.gradle.japicmp.JapicmpTask

plugins {
  id("me.champeau.gradle.japicmp")
}

Projects.applyOsgi(
  project,
  "Export-Package: okhttp3.logging",
  "Automatic-Module-Name: okhttp3.logging",
  "Bundle-SymbolicName: com.squareup.okhttp3.logging"
)

dependencies {
  api(project(":okhttp"))
  compileOnly(Dependencies.jsr305)

  testCompileOnly(Dependencies.jsr305)
  testImplementation(Dependencies.junit)
  testImplementation(project(":mockwebserver"))
  testImplementation(project(":mockwebserver-junit5"))
  testImplementation(project(":okhttp-testing-support"))
  testImplementation(project(":okhttp-tls"))
  testImplementation(Dependencies.assertj)
}

afterEvaluate {
  tasks.dokka {
    outputDirectory = "$rootDir/docs/4.x"
    outputFormat = "gfm"
  }
}

tasks.register<JapicmpTask>("japicmp") {
  dependsOn("jar")
  oldClasspath = files(Projects.baselineJar(project))
  newClasspath = files(tasks.jar.get().archiveFile)
  isOnlyBinaryIncompatibleModified = true
  isFailOnModification = true
  txtOutputFile = file("$buildDir/reports/japi.txt")
  isIgnoreMissingClasses = true
  isIncludeSynthetic = true
}.let(tasks.check::dependsOn)
