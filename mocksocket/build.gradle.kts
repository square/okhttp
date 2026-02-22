import okhttp3.buildsupport.testJavaVersion

plugins {
  kotlin("jvm")
  id("okhttp.publish-conventions")
  id("okhttp.jvm-conventions")
  id("okhttp.quality-conventions")
  id("okhttp.testing-conventions")
}

project.applyJavaModules("mocksocket")

dependencies {
  api(libs.square.okio)
  api(libs.kotlinx.coroutines.core)
  implementation(libs.pkts.core)

  testImplementation(libs.assertk)
  testImplementation(libs.junit.jupiter.api)
  testImplementation(projects.okhttp)
  testRuntimeOnly(libs.junit.jupiter.engine)
}

val testJavaVersion = project.testJavaVersion

if (testJavaVersion >= 11) {
tasks.withType<Test> {
    jvmArgs(
      "--add-opens=java.base/sun.security.ssl=ALL-UNNAMED",
      "--add-opens=java.base/sun.security.util=ALL-UNNAMED",
      "--add-opens=java.base/sun.security.provider=ALL-UNNAMED",
    )
  }
}

kotlin {
  explicitApi()
}
