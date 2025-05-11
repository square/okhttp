import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm")
}

dependencies {
  implementation(projects.okhttp)
  implementation(projects.okhttpBrotli)
  implementation(projects.okhttpCoroutines)
  implementation(projects.okhttpDnsoverhttps)
  implementation(projects.loggingInterceptor)
  implementation(projects.okhttpSse)
  implementation(projects.okhttpTls)
  implementation(projects.okhttpUrlconnection)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(libs.junit)
  testImplementation(libs.kotlin.test.common)
  testImplementation(libs.kotlin.test.junit)
  testImplementation(libs.assertk)

  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> {
  useJUnitPlatform()
  systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
}

val testJavaVersion = System.getProperty("test.java.version", "21").toInt()

java {
  sourceCompatibility = JavaVersion.toVersion(testJavaVersion)
  targetCompatibility = JavaVersion.toVersion(testJavaVersion)
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(21))
  }
}

tasks.withType<KotlinCompile> {
  compilerOptions {
    jvmTarget.set(JvmTarget.fromTarget(testJavaVersion.toString()))
  }
}
