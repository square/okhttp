plugins {
  kotlin("jvm")
}

dependencies {
  testImplementation(projects.okhttp)
  testImplementation(projects.mockwebserver3)
  testRuntimeOnly(projects.mockwebserver3Junit5)
  testImplementation(projects.okhttpTls)
  testImplementation(projects.okhttpTestingSupport)
  testImplementation(Dependencies.httpclient5)
  testImplementation(Dependencies.jettyClient)
  testImplementation(Dependencies.junit)
  testImplementation(Dependencies.assertj)
}

tasks.compileJava {
  options.isWarnings = false
}
