import com.android.build.gradle.internal.tasks.factory.dependsOn
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import org.apache.tools.ant.taskdefs.condition.Os

plugins {
  kotlin("multiplatform")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("com.palantir.graal")
  id("com.github.johnrengelman.shadow")
}

val copyResourcesTemplates = tasks.register<Copy>("copyResourcesTemplates") {
  from("src/jvmMain/resources-templates")
  into("$buildDir/generated/resources-templates")
  expand("projectVersion" to "${project.version}")
  filteringCharset = Charsets.UTF_8.toString()
}

kotlin {
  jvm()

  sourceSets {
    commonMain {
      resources.srcDir(copyResourcesTemplates.get().outputs)
      dependencies {
        api(libs.kotlin.stdlib)
      }
    }

    commonTest {
      dependencies {
        api(libs.kotlin.stdlib)
        implementation(kotlin("test"))
      }
    }

    val jvmMain by getting {
      dependencies {
        api(libs.kotlin.stdlib)
        api(projects.okhttp)
        api(projects.loggingInterceptor)
        api(libs.squareup.okio)
        implementation(libs.clikt)
        api(libs.guava.jre)
      }
    }

    val jvmTest by getting {
      dependencies {
        api(libs.kotlin.stdlib)
        implementation(projects.okhttpTestingSupport)
        api(libs.squareup.okio)
        api(libs.assertk)
        implementation(kotlin("test"))
      }
    }

    // Workaround for https://github.com/palantir/gradle-graal/issues/129
    // Add a second configuration to populate
    // runtimeClasspath vs jvmRuntimeClasspath
    val main by register("main") {
      dependencies {
        implementation(libs.kotlin.stdlib)
        implementation(projects.okhttp)
        implementation(projects.loggingInterceptor)
        implementation(libs.squareup.okio)
        implementation(libs.clikt)
        implementation(libs.guava.jre)
      }
    }
  }
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

// Workaround for https://github.com/palantir/gradle-graal/issues/129
// Copy the jvmJar output into the normal jar location
val copyJvmJar = tasks.register<Copy>("copyJvmJar") {
  val sourceFile = project.tasks.getByName("jvmJar").outputs.files.singleFile
  val destinationFile = project.tasks.getByName("jar").outputs.files.singleFile
  from(sourceFile)
  into(destinationFile.parentFile)
  rename (sourceFile.name, destinationFile.name)
}
tasks.getByName("copyJvmJar").dependsOn(tasks.getByName("jvmJar"))
tasks.getByName("nativeImage").dependsOn(copyJvmJar)

mavenPublishing {
  configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty()))
}
