import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform

plugins {
  kotlin("multiplatform")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("binary-compatibility-validator")
}

kotlin {
  jvm {
    withJava()
  }

  sourceSets {
    getByName("jvmMain") {
      dependencies {
        api(projects.okhttp)
        implementation(libs.kotlinx.coroutines.core)
        api(libs.squareup.okio)
        api(libs.kotlin.stdlib)
      }
    }
    getByName("jvmTest") {
      dependencies {
        implementation(libs.kotlin.test.common)
        implementation(libs.kotlin.test.annotations)
        api(libs.assertk)
        implementation(projects.okhttpTestingSupport)
        implementation(libs.kotlinx.coroutines.test)
        implementation(projects.mockwebserver3Junit5)
      }
    }
  }
}

project.applyOsgi(
  "Export-Package: okhttp3.coroutines",
  "Automatic-Module-Name: okhttp3.coroutines",
  "Bundle-SymbolicName: com.squareup.okhttp3.coroutines"
)

mavenPublishing {
  configure(
    KotlinMultiplatform(javadocJar = JavadocJar.Empty())
  )
}
