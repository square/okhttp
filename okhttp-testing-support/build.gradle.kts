plugins {
  kotlin("jvm")
  id("ru.vyarus.animalsniffer")
}

dependencies {
  api(projects.okhttp)
  api(projects.okhttpTls)
  api(libs.assertj.core)
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
}

animalsniffer {
  isIgnoreFailures = true
}
