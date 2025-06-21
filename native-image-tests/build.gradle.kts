plugins {
  id("org.graalvm.buildtools.native")
  kotlin("jvm")
}

animalsniffer {
  isIgnoreFailures = true
}

val graal by sourceSets.creating

sourceSets {
  named("graal") {}
  test {
    java.srcDirs(
      "../okhttp-brotli/src/test/java",
      "../okhttp-dnsoverhttps/src/test/java",
      "../okhttp-logging-interceptor/src/test/java",
      "../okhttp-sse/src/test/java",
    )
  }
}

dependencies {
  friendsImplementation(projects.okhttp)
  friendsImplementation(projects.okhttpBrotli)
  friendsImplementation(projects.okhttpDnsoverhttps)
  friendsImplementation(projects.loggingInterceptor)
  friendsImplementation(projects.okhttpSse)
  friendsImplementation(projects.okhttpTestingSupport)
  friendsImplementation(projects.okhttpTls)
  friendsImplementation(projects.mockwebserver3)
  friendsImplementation(projects.mockwebserver)
  friendsImplementation(projects.okhttpJavaNetCookiejar)
  friendsImplementation(projects.mockwebserver3Junit5)

  implementation(libs.aqute.resolve)
  implementation(libs.assertk)
  implementation(libs.junit.jupiter.api)
  implementation(libs.junit.jupiter.engine)
  implementation(libs.junit.jupiter.params)
  implementation(libs.junit.platform.console)
  implementation(libs.kotlin.test.common)
  implementation(libs.kotlin.test.junit)
  implementation(libs.squareup.okio.fakefilesystem)

  "graalCompileOnly"(libs.nativeImageSvm)
  "graalCompileOnly"(libs.graal.sdk)
  nativeImageTestCompileOnly(graal.output.classesDirs)
}

graalvmNative {
  testSupport = true

  binaries {
    named("test") {
      buildArgs.add("--features=okhttp3.nativeimage.TestRegistration")
      buildArgs.add("--initialize-at-build-time=org.junit.platform.engine.TestTag")
      buildArgs.add("--strict-image-heap")

      // speed up development testing
      buildArgs.add("-Ob")
    }
  }
}
