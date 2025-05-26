import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.graalvm.buildtools.gradle.dsl.GraalVMExtension
import ru.vyarus.gradle.plugin.animalsniffer.AnimalSnifferExtension

plugins {
  kotlin("jvm")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("com.gradleup.shadow")
}

val testJavaVersion = System.getProperty("test.java.version", "21").toInt()

val copyResourcesTemplates = tasks.register<Copy>("copyResourcesTemplates") {
  from("src/main/resources-templates")
  into(layout.buildDirectory.dir("generated/resources-templates"))
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

  testImplementation(projects.okhttpTestingSupport)
  testApi(libs.assertk)
  testImplementation(kotlin("test"))
}

configure<AnimalSnifferExtension> {
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

if (testJavaVersion >= 11) {
  apply(plugin = "org.graalvm.buildtools.native")

  configure<GraalVMExtension> {
    binaries {
      named("main") {
        imageName = "okcurl"
        mainClass = "okhttp3.curl.MainCommandLineKt"
      }
    }
  }
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Empty()))
}
