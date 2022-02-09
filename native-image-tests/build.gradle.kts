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

  implementation(project(":okhttp"))
  implementation(project(":okhttp-brotli"))
  implementation(project(":okhttp-dnsoverhttps"))
  implementation(project(":logging-interceptor"))
  implementation(project(":okhttp-sse"))
  implementation(project(":okhttp-testing-support"))
  implementation(project(":okhttp-tls"))
  implementation(Dependencies.assertj)
  implementation(project(":mockwebserver3"))
  implementation(project(":mockwebserver"))
  implementation(project(":okhttp-urlconnection"))
  implementation(project(":mockwebserver3-junit4"))
  implementation(project(":mockwebserver3-junit5"))
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
      "../okhttp/src/jvmTest/java",
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
