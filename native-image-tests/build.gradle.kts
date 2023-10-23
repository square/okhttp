import org.apache.tools.ant.taskdefs.condition.Os

plugins {
  id("com.palantir.graal")
  kotlin("jvm")
}

dependencies {
  implementation(libs.assertj.core)
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
  implementation(libs.assertj.core)
  implementation(projects.mockwebserver3)
  implementation(projects.mockwebserver)
  implementation(projects.okhttpJavaNetCookiejar)
  implementation(projects.mockwebserver3Junit4)
  implementation(projects.mockwebserver3Junit5)
  implementation(libs.aqute.resolve)
  implementation(libs.junit.jupiter.api)
  implementation(libs.junit.jupiter.params)
  implementation(libs.assertk)

  implementation(libs.nativeImageSvm)

  compileOnly(libs.findbugs.jsr305)
}

animalsniffer {
  isIgnoreFailures = true
}

sourceSets {
  main {
    java.srcDirs(
      "../okhttp-brotli/src/test/java",
      "../okhttp-dnsoverhttps/src/test/java",
      "../okhttp-logging-interceptor/src/test/java",
      "../okhttp-sse/src/test/java",
    )
  }
}

graal {
  mainClass("okhttp3.RunTestsKt")
  outputName("ConsoleLauncher")
  graalVersion(libs.versions.graalvm.get())
  javaVersion("11")

  option("--no-fallback")
  option("--report-unsupported-elements-at-runtime")
  option("-H:+ReportExceptionStackTraces")
}
