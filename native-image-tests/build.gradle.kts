import org.apache.tools.ant.taskdefs.condition.Os

plugins {
  id("com.palantir.graal")
  kotlin("jvm")
}

dependencies {
  implementation(Dependencies.assertj)
  implementation(Dependencies.junit5Api)
  implementation(Dependencies.junit5JupiterEngine)
  implementation(Dependencies.junitPlatformConsole)
  implementation(Dependencies.okioFakeFileSystem)

  implementation(projects.okhttp)
  implementation(projects.okhttpBrotli)
  implementation(projects.okhttpDnsoverhttps)
  implementation(projects.loggingInterceptor)
  implementation(projects.okhttpSse)
  implementation(projects.okhttpTestingSupport)
  implementation(projects.okhttpTls)
  implementation(Dependencies.assertj)
  implementation(projects.mockwebserver3)
  implementation(projects.mockwebserver)
  implementation(projects.okhttpUrlconnection)
  implementation(projects.mockwebserver3Junit4)
  implementation(projects.mockwebserver3Junit5)
  implementation(Dependencies.bndResolve)
  implementation(Dependencies.junit5Api)
  implementation(Dependencies.junit5JupiterParams)

  implementation(Dependencies.nativeImageSvm)

  compileOnly(Dependencies.jsr305)
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
  graalVersion(Versions.graal)
  javaVersion("11")

  option("--no-fallback")
  option("--allow-incomplete-classpath")
  option("--report-unsupported-elements-at-runtime")
  option("-H:+ReportExceptionStackTraces")
}
