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
  if (kmpJsEnabled) {
    js {
      compilations.all {
        kotlinOptions {
          moduleKind = "umd"
          sourceMap = true
          metaInfo = true
        }
      }
      nodejs {
        testTask {
          useMocha {
            timeout = "30s"
          }
        }
      }
      browser {
      }
    }
  }

  sourceSets {
    commonMain {
      dependencies {
        api(libs.squareup.okio)
        api(projects.okhttp)
        implementation(libs.kotlinx.coroutines.core)
      }
    }
    val commonTest by getting {
      dependencies {
        implementation(libs.kotlin.test.common)
        implementation(libs.kotlin.test.annotations)
        api(libs.assertk)
      }
    }
    val nonJvmMain = create("nonJvmMain") {
      dependencies {
        dependsOn(sourceSets.commonMain.get())
        api(projects.okhttp)
        implementation(libs.kotlinx.coroutines.core)
      }
    }
    val nonJvmTest = create("nonJvmTest") {
      dependencies {
        dependsOn(sourceSets.commonTest.get())
      }
    }

    getByName("jvmMain") {
      dependencies {
        api(libs.squareup.okio)
        api(libs.kotlin.stdlib)
      }
    }
    getByName("jvmTest") {
      dependencies {
        dependsOn(commonTest)
        implementation(projects.okhttpTestingSupport)
        implementation(libs.kotlinx.coroutines.test)
        implementation(projects.mockwebserver3Junit5)
      }

      getByName("jsMain") {
        dependencies {
          dependsOn(nonJvmMain)
          api(projects.okhttp)
          api(libs.squareup.okio)
          api(libs.kotlin.stdlib)
        }
      }

      getByName("jsTest") {
        dependencies {
          dependsOn(nonJvmTest)
          implementation(libs.kotlin.test.js)
        }
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
    KotlinMultiplatform(javadocJar = JavadocJar.Dokka("dokkaGfm"))
  )
}
