import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  kotlin("jvm")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("binary-compatibility-validator")
  id("ru.vyarus.animalsniffer")
}

dependencies {
  api(libs.squareup.okio)
  api(projects.okhttp)
  compileOnly(libs.findbugs.jsr305)
  compileOnly(libs.animalsniffer.annotations)
  compileOnly(libs.robolectric.android)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(projects.mockwebserver3Junit5)
  testImplementation(libs.junit)
  testImplementation(libs.assertj.core)
}

mavenPublishing {
  configure(
    KotlinJvm(
      javadocJar = JavadocJar.Dokka("dokkaGfm")
    )
  )
}
