import com.android.build.gradle.internal.tasks.factory.dependsOn
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.apache.tools.ant.taskdefs.condition.Os

plugins {
  kotlin("jvm")
  kotlin("kapt")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
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

dependencies {
  api(projects.okhttp)
  api(projects.loggingInterceptor)
  implementation(libs.picocli)
  implementation(libs.guava.jre)

  kapt(libs.picocli.compiler)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.assertj.core)
}

tasks.shadowJar {
  mergeServiceFiles()
}

graal {
  mainClass("okhttp3.curl.Main")
  outputName("okcurl")
  graalVersion(libs.versions.graalvm.get())
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

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGfm")))
}

tasks.register<Copy>("copyResourcesTemplates") {
  from("src/main/resources-templates")
  into("$buildDir/generated/resources-templates")
  expand("projectVersion" to "${project.version}")
  filteringCharset = Charsets.UTF_8.toString()
}.let {
  tasks.processResources.dependsOn(it)
  tasks["javaSourcesJar"].dependsOn(it)
}
