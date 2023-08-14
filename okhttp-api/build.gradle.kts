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
        implementation(libs.kotlinx.coroutines.core)
      }
    }

    getByName("jvmMain") {
      dependencies {
        api(libs.squareup.okio)
        api(libs.kotlin.stdlib)
      }
    }

    getByName("jsMain") {
      dependencies {
        dependsOn(nonJvmMain)
        api(libs.squareup.okio)
        api(libs.kotlin.stdlib)
      }
    }
  }
}

project.applyOsgi(
  "Export-Package: okhttp3.api",
  "Automatic-Module-Name: okhttp3.api",
  "Bundle-SymbolicName: com.squareup.okhttp3.api"
)

mavenPublishing {
  configure(
    KotlinMultiplatform(javadocJar = JavadocJar.Dokka("dokkaGfm"))
  )
}
