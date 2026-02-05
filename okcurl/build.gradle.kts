import kotlinx.validation.ApiValidationExtension
import okhttp3.buildsupport.testJavaVersion
import org.graalvm.buildtools.gradle.dsl.GraalVMExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import ru.vyarus.gradle.plugin.animalsniffer.AnimalSnifferExtension

plugins {
  kotlin("jvm")
  id("okhttp.publish-conventions")
  id("okhttp.jvm-conventions")
  id("okhttp.quality-conventions")
  id("okhttp.testing-conventions")
  id("com.gradleup.shadow")
}

tasks.withType<KotlinCompile> {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

tasks.withType<JavaCompile> {
  sourceCompatibility = JvmTarget.JVM_17.target
  targetCompatibility = JvmTarget.JVM_17.target
}

val copyResourcesTemplates =
  tasks.register<Copy>("copyResourcesTemplates") {
    from("src/main/resources-templates")
    into(layout.buildDirectory.dir("generated/resources-templates"))
    expand("projectVersion" to "${project.version}")
    filteringCharset = Charsets.UTF_8.toString()
  }

configure<JavaPluginExtension> {
  sourceSets.getByName("main").resources.srcDir(copyResourcesTemplates.get().outputs)
}

dependencies {
  api(projects.okhttp)
  api(projects.loggingInterceptor)
  api(libs.square.okio)
  implementation(libs.clikt)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(projects.mockwebserver3)
  testImplementation(projects.mockwebserver3Junit5)
  testImplementation(libs.junit)
  testImplementation(libs.assertk)
  testImplementation(kotlin("test"))
}

val jvmSignature = configurations.getByName("jvmSignature")
configure<AnimalSnifferExtension> {
  // Only check JVM
  signatures = jvmSignature
}

configure<ApiValidationExtension> {
  validationDisabled = true
}

tasks.jar {
  manifest {
    attributes("Main-Class" to "okhttp3.curl.MainCommandLineKt")
  }
}

tasks.shadowJar {
  mergeServiceFiles()
}

tasks.withType<Test> {
  val javaVersion = project.testJavaVersion
  onlyIf("native build requires Java 17") {
    javaVersion > 17
  }
}

apply(plugin = "org.graalvm.buildtools.native")

configure<GraalVMExtension> {
  binaries {
    named("main") {
      imageName = "okcurl"
      mainClass = "okhttp3.curl.MainCommandLineKt"
      if (System.getProperty("os.name").lowercase().contains("windows")) {
        // windows requires a slightly different approach for some things
      } else {
        buildArgs("--no-fallback")
      }
    }
  }
}
