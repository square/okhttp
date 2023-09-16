import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  kotlin("jvm")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("binary-compatibility-validator")
}

tasks.jar {
  manifest {
    attributes("Automatic-Module-Name" to "mockwebserver3")
  }
}

dependencies {
  api(projects.okhttp)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(projects.okhttpTls)
  testRuntimeOnly(projects.mockwebserver3Junit5)
  testImplementation(libs.junit)
  testImplementation(libs.assertj.core)
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Empty()))
}
