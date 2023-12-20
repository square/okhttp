import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.apache.tools.ant.taskdefs.condition.Os

plugins {
  kotlin("jvm")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("com.palantir.graal")
  id("com.github.johnrengelman.shadow")
}

val copyResourcesTemplates = tasks.register<Copy>("copyResourcesTemplates") {
  from("src/main/resources-templates")
  into("$buildDir/generated/resources-templates")
  expand("projectVersion" to "${project.version}")
  filteringCharset = Charsets.UTF_8.toString()
}

kotlin {
  sourceSets {
    val main by getting {
      resources.srcDir(copyResourcesTemplates.get().outputs)
    }
  }
}

dependencies {
  api(libs.kotlin.stdlib)
  api(projects.okhttp)
  api(projects.loggingInterceptor)
  api(libs.squareup.okio)
  implementation(libs.clikt)
  api(libs.guava.jre)

  testImplementation(projects.okhttpTestingSupport)
  testApi(libs.assertk)
  testImplementation(kotlin("test"))
}

animalsniffer {
  isIgnoreFailures = true
}

tasks.jar {
  manifest {
    attributes("Automatic-Module-Name" to "okhttp3.curl")
    attributes("Main-Class" to "okhttp3.curl.MainCommandLineKt")
  }
}

tasks.shadowJar {
  mergeServiceFiles()
}

graal {
  mainClass("okhttp3.curl.MainCommandLineKt")
  outputName("okcurl")
  graalVersion(libs.versions.graalvm.get())
  javaVersion("11")

  option("--no-fallback")

  if (Os.isFamily(Os.FAMILY_WINDOWS)) {
    // May be possible without, but autodetection is problematic on Windows 10
    // see https://github.com/palantir/gradle-graal
    // see https://www.graalvm.org/docs/reference-manual/native-image/#prerequisites
    windowsVsVarsPath("C:\\Program Files (x86)\\Microsoft Visual Studio\\2019\\BuildTools\\VC\\Auxiliary\\Build\\vcvars64.bat")
  }
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Empty()))
}
