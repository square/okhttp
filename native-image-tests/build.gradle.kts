import org.jetbrains.kotlin.gradle.utils.extendsFrom

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
  implementation(libs.junit.jupiter.api)
  implementation(libs.junit.jupiter.engine)
  implementation(libs.junit.platform.console)
  implementation(libs.squareup.okio.fakefilesystem)

  implementation(projects.okhttp)
  implementation(projects.okhttpBrotli)
  implementation(projects.okhttpDnsoverhttps)
  implementation(projects.loggingInterceptor)
  implementation(projects.okhttpSse)
  implementation(projects.okhttpTestingSupport)
  implementation(projects.okhttpTls)
  implementation(projects.mockwebserver3)
  implementation(projects.mockwebserver)
  implementation(projects.okhttpJavaNetCookiejar)
  implementation(projects.mockwebserver3Junit4)
  implementation(projects.mockwebserver3Junit5)
  implementation(libs.aqute.resolve)
  implementation(libs.junit.jupiter.api)
  implementation(libs.junit.jupiter.params)
  implementation(libs.assertk)
  implementation(libs.kotlin.test.common)
  implementation(libs.kotlin.test.junit)

  compileOnly(libs.findbugs.jsr305)

  println(sourceSets.names)

//  "graalTestCompileOnly"(libs.nativeImageSvm)
//  "graalTestCompileOnly"(libs.graal.sdk)
//  nativeImageTestCompileOnly(graal.output.classesDirs)
}

configurations {
//  getByName("nativeImageTestImplementation").extendsFrom(getByName("graalImplementation"))
}

graalvmNative {
  binaries {
    named("test") {
      buildArgs.add("--features=okhttp3.nativeImage.TestRegistration")
      buildArgs.add("--initialize-at-build-time=org.junit.platform.engine.TestTag")
      // speed up development testing
      buildArgs.add("-Ob")
    }
  }
}
