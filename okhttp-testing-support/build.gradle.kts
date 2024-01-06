plugins {
  kotlin("jvm")
  id("ru.vyarus.animalsniffer")
}

dependencies {
  api(libs.squareup.okio)
  api(projects.okhttp)
  api(projects.okhttpTls)
  api(libs.assertk)
  api(libs.bouncycastle.bcprov)
  implementation(libs.bouncycastle.bcpkix)
  implementation(libs.bouncycastle.bctls)
  api(libs.conscrypt.openjdk)
  api(libs.openjsse)

  api(variantOf(libs.amazonCorretto) {
    classifier("linux-x86_64")
  })

  api(libs.hamcrestLibrary)
  api(libs.junit.jupiter.api)
  api(libs.junit.jupiter.params)

  api(libs.junit.pioneer)

  compileOnly(libs.findbugs.jsr305)
  compileOnly(libs.robolectric.android)

  testImplementation(libs.kotlin.test.common)
  testImplementation(libs.kotlin.test.junit)
}

animalsniffer {
  isIgnoreFailures = true
}
