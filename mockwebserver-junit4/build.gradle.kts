import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  kotlin("jvm")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("binary-compatibility-validator")
}

project.applyJavaModules("mockwebserver3.junit4")

dependencies {
  api(projects.okhttp)
  api(projects.mockwebserver3)
  api(libs.junit)

  testImplementation(libs.assertk)
  testImplementation(libs.junit.vintage.engine)
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Empty()))
}
