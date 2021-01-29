dependencies {
  api(project(":okhttp"))
  api(Dependencies.assertj)
  api(Dependencies.bouncycastle)
  implementation(Dependencies.bouncycastlepkix)
  implementation(Dependencies.bouncycastletls)
  api(Dependencies.conscrypt)
  api(Dependencies.corretto)
  api(Dependencies.openjsse)
  api(Dependencies.hamcrest)
  api(Dependencies.junit5Api)
  api(Dependencies.junit5JupiterParams)

  compileOnly(Dependencies.jsr305)
  compileOnly(Dependencies.android)
}

animalsniffer {
  setIgnoreFailures(true)
}
