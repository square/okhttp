plugins {
  id("org.graalvm.buildtools.native")
  kotlin("jvm")
}

animalsniffer {
  isIgnoreFailures = true
}

// TODO reenable other tests
// https://github.com/square/okhttp/issues/8901
//sourceSets {
//  test {
//    java.srcDirs(
//      "../okhttp-brotli/src/test/java",
//      "../okhttp-dnsoverhttps/src/test/java",
//      "../okhttp-logging-interceptor/src/test/java",
//      "../okhttp-sse/src/test/java",
//    )
//  }
//}

dependencies {
  implementation(projects.okhttp)

  testImplementation(projects.mockwebserver3Junit5)
  testImplementation(libs.assertk)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testImplementation(libs.kotlin.junit5)
  testImplementation(libs.junit.jupiter.params)
}

graalvmNative {
  testSupport = true

  binaries {
    named("test") {
      buildArgs.add("--strict-image-heap")

      // speed up development testing
      buildArgs.add("-Ob")
    }
  }
}
