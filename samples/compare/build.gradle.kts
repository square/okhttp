plugins {
  kotlin("jvm")
}

dependencies {
  testImplementation(projects.okhttp)
  testImplementation(projects.mockwebserver3)
  testRuntimeOnly(projects.mockwebserver3Junit5)
  testImplementation(projects.okhttpTls)
  testImplementation(projects.okhttpTestingSupport)
  testImplementation(libs.httpcomponents.httpclient5)
  testImplementation(Dependencies.jettyClient)
  testImplementation(libs.junit)
  testImplementation(libs.assertj.core)
}

tasks.compileJava {
  options.isWarnings = false
}
