import com.android.build.gradle.internal.tasks.factory.dependsOn
import me.champeau.gradle.japicmp.JapicmpTask

plugins {
  id("me.champeau.gradle.japicmp")
}

tasks.jar {
  manifest {
    attributes("Automatic-Module-Name" to "okhttp3.mockwebserver")
  }
}

dependencies {
  api(project(":okhttp"))
  api(project(":mockwebserver"))
  api(Dependencies.junit)

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
  packageExcludes = listOf(
    "okhttp3.internal.duplex",
    "okhttp3.mockwebserver.internal",
    "okhttp3.mockwebserver.internal.duplex",
  )
  classExcludes = listOf(
    // Became "final" in 4.10.0.
    "okhttp3.mockwebserver.QueueDispatcher"
  )
}.let(tasks.check::dependsOn)
