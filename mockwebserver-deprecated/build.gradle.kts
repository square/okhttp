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
    attributes("Automatic-Module-Name" to "okhttp3.mockwebserver")
  }
}

dependencies {
  api(projects.okhttp)
  api(projects.mockwebserver3)
  api(libs.junit)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(projects.okhttpTls)
  testImplementation(libs.assertj.core)
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Empty()))
}
