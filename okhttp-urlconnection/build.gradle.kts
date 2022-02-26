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
  "Fragment-Host: com.squareup.okhttp3; bundle-version=\"\${range;[==,+);\${version_cleanup;${projects.okhttp.version}}}\"",
  "Automatic-Module-Name: okhttp3.urlconnection",
  "Bundle-SymbolicName: com.squareup.okhttp3.urlconnection",
  "-removeheaders: Private-Package"
)

dependencies {
  api(projects.okhttp)
  compileOnly(libs.findbugs.jsr305)
  compileOnly(libs.animalsniffer.annotations)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(projects.okhttpTls)
  testImplementation(projects.mockwebserver)
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
}.let(tasks.check::dependsOn)


mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGfm")))
}
