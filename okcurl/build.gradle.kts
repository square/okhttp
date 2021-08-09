import com.android.build.gradle.internal.tasks.factory.dependsOn
import java.nio.charset.StandardCharsets
import org.apache.tools.ant.taskdefs.condition.Os

plugins {
  kotlin("kapt")
  id("com.palantir.graal")
  id("com.github.johnrengelman.shadow")
}

tasks.jar {
  manifest {
    attributes("Automatic-Module-Name" to "okhttp3.curl")
    attributes("Main-Class" to "okhttp3.curl.Main")
  }
}

// resources-templates.
sourceSets {
  main {
    resources.srcDirs("$buildDir/generated/resources-templates")
  }
}

tasks.register<Copy>("copyResourcesTemplates") {
  from("src/main/resources-templates")
  into("$buildDir/generated/resources-templates")
  expand("projectVersion" to "${project.version}")
  filteringCharset = StandardCharsets.UTF_8.toString()
}.let {
  tasks.processResources.dependsOn(it)
  tasks.sourcesJar.dependsOn(it)
}

dependencies {
  api(project(":okhttp"))
  api(project(":okhttp-logging-interceptor"))
  implementation(Dependencies.picocli)
  implementation(Dependencies.guava)

  kapt(Dependencies.picocliCompiler)

  testImplementation(project(":okhttp-testing-support"))
  testImplementation(Dependencies.junit5Api)
  testImplementation(Dependencies.assertj)
}

tasks.shadowJar {
  mergeServiceFiles()
}

graal {
  mainClass("okhttp3.curl.Main")
  outputName("okcurl")
  graalVersion("21.2.0")
  javaVersion("11")

  option("--no-fallback")
  option("--allow-incomplete-classpath")

  if (Os.isFamily(Os.FAMILY_WINDOWS)) {
    // May be possible without, but autodetection is problematic on Windows 10
    // see https://github.com/palantir/gradle-graal
    // see https://www.graalvm.org/docs/reference-manual/native-image/#prerequisites
    windowsVsVarsPath("C:\\Program Files (x86)\\Microsoft Visual Studio\\2019\\BuildTools\\VC\\Auxiliary\\Build\\vcvars64.bat")
  }
}
