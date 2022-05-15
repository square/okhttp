plugins {
  kotlin("jvm")
  kotlin("kapt")
}

dependencies {
  implementation(projects.okhttp)
  implementation(projects.mockwebserver)
  implementation(projects.okhttpTestingSupport)
  implementation(projects.okhttpTls)
  implementation(projects.okhttpCoroutines)
  implementation(libs.animalsniffer.annotations)
  implementation(libs.squareup.moshi)
  implementation(libs.squareup.okio.fakefilesystem)
  kapt(libs.squareup.moshi.compiler)
}

task("execute", JavaExec::class) {
  mainClass.set("okhttp3.recipes.kt.LoadBalancingKt")
  classpath = sourceSets["main"].runtimeClasspath
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}

tasks.compileJava {
  options.isWarnings = false
}
