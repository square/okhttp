import org.apache.tools.ant.taskdefs.condition.Os

plugins {
  id("com.palantir.graal")
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
  implementation(project(":okhttp-logging-interceptor"))
  implementation(project(":okhttp-sse"))
  implementation(project(":okhttp-testing-support"))
  implementation(project(":okhttp-tls"))
  implementation(Dependencies.assertj)
  implementation(project(":mockwebserver"))
  implementation(project(":mockwebserver-deprecated"))
  implementation(project(":okhttp-urlconnection"))
  implementation(project(":mockwebserver-junit4"))
  implementation(project(":mockwebserver-junit5"))
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
  // Not included in IDE as this confuses Intellij for obvious reasons.
  main {
    java.srcDirs(
      "../okhttp/src/test/java",
      "../okhttp-brotli/src/test/java",
      "../okhttp-dnsoverhttps/src/test/java",
      "../okhttp-logging-interceptor/src/test/java",
      "../okhttp-sse/src/test/java"
    )
  }
}

graal {
  mainClass("okhttp3.RunTestsKt")
  outputName("ConsoleLauncher")
  graalVersion("21.2.0")
  javaVersion("11")

  option("--no-fallback")
  option("--allow-incomplete-classpath")
  option("--report-unsupported-elements-at-runtime")
  option("-H:+ReportExceptionStackTraces")

  if (Os.isFamily(Os.FAMILY_WINDOWS)) {
    // May be possible without, but autodetection is problematic on Windows 10
    // see https://github.com/palantir/gradle-graal
    // see https://www.graalvm.org/docs/reference-manual/native-image/#prerequisites
    windowsVsVarsPath("C:\\Program Files (x86)\\Microsoft Visual Studio\\2019\\BuildTools\\VC\\Auxiliary\\Build\\vcvars64.bat")
  }
}
