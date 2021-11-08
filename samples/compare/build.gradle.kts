dependencies {
  testImplementation(project(":okhttp"))
  testImplementation(project(":mockwebserver"))
  testRuntimeOnly(project(":mockwebserver-junit5"))
  testImplementation(project(":okhttp-tls"))
  testImplementation(project(":okhttp-testing-support"))
  testImplementation(Dependencies.httpclient5)
  testImplementation(Dependencies.jettyClient)
  testImplementation(Dependencies.junit)
  testImplementation(Dependencies.assertj)
}

tasks.compileJava {
  options.isWarnings = false
}
