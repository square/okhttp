plugins {
  kotlin("jvm")
}

dependencies {
  testImplementation(projects.okhttp)
  testImplementation(project(":mockwebserver3"))
  testRuntimeOnly(project(":mockwebserver3-junit5"))
  testImplementation(project(":okhttp-tls"))
  testImplementation(projects.okhttpTestingSupport)
  testImplementation(Dependencies.httpclient5)
  testImplementation(Dependencies.jettyClient)
  testImplementation(Dependencies.junit)
  testImplementation(Dependencies.assertj)
}

tasks.compileJava {
  options.isWarnings = false
}
