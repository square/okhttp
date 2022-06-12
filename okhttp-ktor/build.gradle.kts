import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform

plugins {
  kotlin("multiplatform")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("binary-compatibility-validator")
}

kotlin {
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
    val nonJvmMain = create("nonJvmMain") {
      dependencies {
        api(projects.okhttp)
        api(libs.squareup.okio)
        api(libs.kotlin.stdlib)
        api("io.ktor:ktor-client-core:2.0.2")
      }
    }

    val jsMain = getByName("jsMain") {
      dependencies {
        dependsOn(nonJvmMain)
        implementation("com.squareup.okio:okio-js:3.1.0")
      }
    }

    getByName("jsTest") {
      dependencies {
        dependsOn(nonJvmMain)
        dependsOn(jsMain)
        implementation(libs.kotlin.test.js)
        implementation(libs.kotlinx.coroutines.test)
        implementation(libs.assertk)
      }
    }
  }
}

mavenPublishing {
  configure(
    KotlinMultiplatform(javadocJar = JavadocJar.Dokka("dokkaGfm"))
  )
}
