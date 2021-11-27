import com.android.build.gradle.internal.tasks.factory.dependsOn
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import me.champeau.gradle.japicmp.JapicmpTask

plugins {
  kotlin("jvm")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("me.champeau.gradle.japicmp")
}

tasks.jar {
  manifest {
    attributes("Automatic-Module-Name" to "okhttp3.mockwebserver")
  }
}

dependencies {
  api(project(":okhttp"))
  api(project(":mockwebserver3"))
  api(Dependencies.junit)

  testImplementation(project(":okhttp-testing-support"))
  testImplementation(project(":okhttp-tls"))
  testImplementation(Dependencies.assertj)
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
    "okhttp3.internal.duplex",
    "okhttp3.mockwebserver.internal",
    "okhttp3.mockwebserver.internal.duplex",
  )
  classExcludes = listOf(
    // Became "final" in 4.10.0.
    "okhttp3.mockwebserver.QueueDispatcher"
  )
}.let(tasks.check::dependsOn)


mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGfm")))
}
