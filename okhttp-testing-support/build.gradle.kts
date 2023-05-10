plugins {
  kotlin("multiplatform")
  id("ru.vyarus.animalsniffer")
}

kotlin {
  jvm {
    withJava()
  }
  if (kmpJsEnabled) {
    js(IR) {
      nodejs()
    }
  }

  sourceSets {
    val commonMain by getting {
      dependencies {
        api(libs.squareup.okio)
      }
    }
    val jsMain by getting {
      dependencies {
        implementation(libs.squareup.okio.nodefilesystem)
      }
    }
    val jvmMain by getting {
      dependencies {
        api(projects.okhttp)
        api(projects.okhttpTls)
        api(libs.assertj.core)
        api(libs.bouncycastle.bcprov)
        implementation(libs.bouncycastle.bcpkix)
        implementation(libs.bouncycastle.bctls)
        api(libs.conscrypt.openjdk)
        api(libs.openjsse)

        api(libs.amazonCorretto)

        api(libs.hamcrestLibrary)
        api(libs.junit.jupiter.api)
        api(libs.junit.jupiter.params)

        api(libs.junit.pioneer)

        compileOnly(libs.findbugs.jsr305)
        compileOnly(libs.robolectric.android)
      }
    }
  }
}

val jvmMainApi by configurations.getting

dependencies {
  jvmMainApi(variantOf(libs.amazonCorretto) {
    classifier("linux-x86_64")
  })
}

animalsniffer {
  isIgnoreFailures = true
}
