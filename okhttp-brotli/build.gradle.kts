Projects.applyOsgi(
  project,
  "Export-Package: okhttp3.brotli",
  "Automatic-Module-Name: okhttp3.brotli",
  "Bundle-SymbolicName: com.squareup.okhttp3.brotli"
)

dependencies {
  api(project(":okhttp"))
  api(Dependencies.brotli)
  compileOnly(Dependencies.jsr305)

  testImplementation(project(":okhttp-testing-support"))
  testImplementation(Dependencies.conscrypt)
  testImplementation(Dependencies.junit)
  testImplementation(Dependencies.assertj)
}

afterEvaluate {
  tasks.dokka {
    outputDirectory = "$rootDir/docs/4.x"
    outputFormat = "gfm"
  }
}
