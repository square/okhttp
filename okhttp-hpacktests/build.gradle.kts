dependencies {
  testImplementation(Dependencies.okio)
  testImplementation(Dependencies.moshi)
  testImplementation(project(":okhttp"))
  testImplementation(project(":okhttp-testing-support"))
  testImplementation(project(":mockwebserver-deprecated"))
  testImplementation(Dependencies.junit)
  testImplementation(Dependencies.assertj)
}
