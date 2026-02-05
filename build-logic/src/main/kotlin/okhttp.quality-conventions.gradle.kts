import com.vanniktech.maven.publish.MavenPublishBaseExtension
import ru.vyarus.gradle.plugin.animalsniffer.AnimalSnifferExtension
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.withType
import ru.vyarus.gradle.plugin.animalsniffer.AnimalSniffer

plugins {
  id("okhttp.base-conventions")
  id("checkstyle")
  id("ru.vyarus.animalsniffer")
  id("com.android.lint")
  id("com.diffplug.spotless")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun library(alias: String) = libs.findLibrary(alias).get().get().let {
  "${it.module.group}:${it.module.name}:${it.versionConstraint.requiredVersion}"
}
fun version(alias: String) = libs.findVersion(alias).get().toString()

tasks.withType<Checkstyle>().configureEach {
  exclude("**/CipherSuite.java")
}

val checkstyleConfig = configurations.maybeCreate("checkstyleConfig")
dependencies {
  add("checkstyleConfig", library("checkstyle")) {
    isTransitive = false
  }
}

configure<CheckstyleExtension> {
  config = resources.text.fromArchiveEntry(checkstyleConfig, "google_checks.xml")
  toolVersion = version("checkstyle")

  val sourceSets = project.extensions.findByType<SourceSetContainer>()
  val main = sourceSets?.findByName("main") ?: sourceSets?.findByName("jvmMain")
  if (main != null) {
    this.sourceSets = listOf(main)
  }
}

val androidSignature = configurations.maybeCreate("androidSignature")
val jvmSignature = configurations.maybeCreate("jvmSignature")

configure<AnimalSnifferExtension> {
  annotation = "okhttp3.internal.SuppressSignatureCheck"

  val sourceSets = project.extensions.findByType<SourceSetContainer>()
  val main = sourceSets?.findByName("main") ?: sourceSets?.findByName("jvmMain")
  if (main != null) {
    this.sourceSets = listOf(main)
  }

  signatures = androidSignature + jvmSignature
  failWithoutSignatures = false
}

// Default to only published modules
project.tasks.withType<AnimalSniffer> {
  val hasMavenPublish = project.extensions.findByType<MavenPublishBaseExtension>() != null
  onlyIf {
    hasMavenPublish
  }
}

dependencies {
  // Logic for signatures should be moved to the applying module or configured via extension
  // For now, we'll keep the standard ones and allow modules to add more
  androidSignature(library("signature-android-apilevel21")) { artifact { type = "signature" } }
  jvmSignature(library("codehaus-signature-java18")) { artifact { type = "signature" } }

  "lintChecks"(library("androidx-lint-gradle"))
}

configure<com.android.build.api.dsl.Lint> {
  xmlReport = true
  checkDependencies = true
}

configure<com.diffplug.gradle.spotless.SpotlessExtension> {
  kotlin {
    target("src/**/*.kt")
    targetExclude("**/kotlinTemplates/**/*.kt")
    ktlint()
    suppressLintsFor {
      step = "ktlint"
      shortCode = "standard:mixed-condition-operators"
    }
  }
  kotlinGradle {
    target("*.kts")
    ktlint()
  }
}
