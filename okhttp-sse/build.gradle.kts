import com.android.build.gradle.internal.tasks.factory.dependsOn
import me.champeau.gradle.japicmp.JapicmpTask

plugins {
  id("me.champeau.gradle.japicmp")
}

Projects.applyOsgi(
  project,
  "Export-Package: okhttp3.sse",
  "Automatic-Module-Name: okhttp3.sse",
  "Bundle-SymbolicName: com.squareup.okhttp3.sse"
)

dependencies {
  api(project(":okhttp"))
  compileOnly(Dependencies.jsr305)

  testImplementation(project(":okhttp-testing-support"))
  testImplementation(project(":mockwebserver"))
  testImplementation(project(":mockwebserver-junit5"))
  testImplementation(Dependencies.junit)
  testImplementation(Dependencies.assertj)
  testCompileOnly(Dependencies.jsr305)
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
  packageExcludes = listOf(
    "okhttp3.internal.sse"
  )
}.let(tasks.check::dependsOn)
