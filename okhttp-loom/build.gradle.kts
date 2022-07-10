import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  kotlin("jvm")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("binary-compatibility-validator")
}

project.applyOsgi(
  "Export-Package: okhttp3.loom",
  "Automatic-Module-Name: okhttp3.loom",
  "Bundle-SymbolicName: com.squareup.okhttp3.loom"
)

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(19))
  }
}

tasks.withType<Test> {
  val javaToolchains = project.extensions.getByType<JavaToolchainService>()
  javaLauncher.set(javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(19))
  })
  jvmArgs("--enable-preview")
}

tasks.withType<JavaCompile> {
  options.compilerArgs.add("--enable-preview")
}

dependencies {
  api(projects.okhttp)
  compileOnly(libs.findbugs.jsr305)

  testImplementation(projects.okhttp)
  testImplementation(projects.mockwebserver3)
  testImplementation(projects.mockwebserver3Junit5)
  testImplementation(projects.okhttpTestingSupport)
  testImplementation(libs.conscrypt.openjdk)
  testImplementation(libs.junit)
  testImplementation(libs.assertj.core)
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGfm")))
}
