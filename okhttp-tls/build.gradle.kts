import com.android.build.gradle.internal.tasks.factory.dependsOn
import me.champeau.gradle.japicmp.JapicmpTask

plugins {
  id("me.champeau.gradle.japicmp")
}

Projects.applyOsgi(
  project,
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
  testImplementation(project(":mockwebserver-junit5"))
  testImplementation(Dependencies.junit)
  testImplementation(Dependencies.assertj)
}

afterEvaluate {
  tasks.dokka {
    outputDirectory = "$rootDir/docs/4.x"
    outputFormat = "gfm"
  }
}

animalsniffer {
  // InsecureExtendedTrustManager (API 24+)
  ignore = listOf("javax.net.ssl.X509ExtendedTrustManager")
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
    "okhttp3.tls.internal"
  )
}.let(tasks.check::dependsOn)
