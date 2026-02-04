package okhttp3.buildsupport

import org.gradle.api.Project

val Project.platform: String
  get() = findProperty("okhttp.platform")?.toString() ?: "jdk9"

val Project.testJavaVersion: Int
  get() = findProperty("test.java.version")?.toString()?.toInt() ?: 21

val Project.androidBuild: Boolean
  get() = findProperty("androidBuild")?.toString()?.toBoolean() ?: false

val Project.alpnBootVersion: String?
  get() = findProperty("alpn.boot.version")?.toString()
