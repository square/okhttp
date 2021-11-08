import com.android.build.gradle.internal.tasks.factory.dependsOn
import me.champeau.gradle.japicmp.JapicmpTask

plugins {
  id("me.champeau.gradle.japicmp")
}

val mainProj: Project by lazy { project(":okhttp") }

Projects.applyOsgi(
  project,
  "Fragment-Host: com.squareup.okhttp3; bundle-version=\"\${range;[==,+);\${version_cleanup;${mainProj.version}}}\"",
  "Automatic-Module-Name: okhttp3.urlconnection",
  "Bundle-SymbolicName: com.squareup.okhttp3.urlconnection",
  "-removeheaders: Private-Package"
)

dependencies {
  api(mainProj)
  compileOnly(Dependencies.jsr305)
  compileOnly(Dependencies.animalSniffer)

  testImplementation(project(":okhttp-testing-support"))
  testImplementation(project(":okhttp-tls"))
  testImplementation(project(":mockwebserver-deprecated"))
  testImplementation(Dependencies.junit)
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
