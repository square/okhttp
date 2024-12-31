plugins {
  kotlin("jvm")
}

dependencies {
  testImplementation(projects.okhttp)
  testImplementation(projects.mockwebserver3)
  testRuntimeOnly(projects.mockwebserver3Junit5)
  testImplementation(projects.okhttpTls)
  testImplementation(projects.okhttpTestingSupport)
  testImplementation(libs.httpClient5)
  testImplementation(libs.jettyClient)
  testImplementation(libs.junit)
  testImplementation(libs.assertk)
}

tasks.compileJava {
  options.isWarnings = false
}
