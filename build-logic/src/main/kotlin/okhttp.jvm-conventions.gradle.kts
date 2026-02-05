import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import kotlinx.validation.ApiValidationExtension
import org.gradle.api.artifacts.VersionCatalogsExtension
import okhttp3.buildsupport.testJavaVersion

plugins {
  id("okhttp.base-conventions")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun library(alias: String) = libs.findLibrary(alias).get().get().let {
  "${it.module.group}:${it.module.name}:${it.versionConstraint.requiredVersion}"
}
fun version(alias: String) = libs.findVersion(alias).get().toString()

extensions.configure<JavaPluginExtension> {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(21))
  }
}

tasks.withType<JavaCompile> {
  options.encoding = Charsets.UTF_8.toString()
  if (name.contains("Java9")) {
    sourceCompatibility = "9"
    targetCompatibility = "9"
  } else {
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
  }
}

tasks.withType<KotlinCompile> {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_1_8)
    freeCompilerArgs.addAll(
      "-Xjvm-default=all",
      "-Xexpect-actual-classes",
    )
  }
}
