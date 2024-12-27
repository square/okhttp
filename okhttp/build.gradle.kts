@file:Suppress("UnstableApiUsage")

import aQute.bnd.gradle.BundleTaskExtension
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import ru.vyarus.gradle.plugin.animalsniffer.AnimalSnifferExtension
import java.util.Base64

plugins {
  kotlin("multiplatform")
  id("com.android.library")
  kotlin("plugin.serialization")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("binary-compatibility-validator")
}

val platform = System.getProperty("okhttp.platform", "jdk9")
val testJavaVersion = System.getProperty("test.java.version", "21").toInt()

fun ByteArray.toByteStringExpression(): String {
  return "\"${Base64.getEncoder().encodeToString(this@toByteStringExpression)}\".decodeBase64()!!"
}

val copyKotlinTemplates = tasks.register<Copy>("copyKotlinTemplates") {
  val kotlinTemplatesOutput = layout.buildDirectory.dir("generated/sources/kotlinTemplates")

  from("src/commonJvmAndroid/kotlinTemplates")
  into(kotlinTemplatesOutput)

  // Tag as an input to regenerate after an update
  inputs.file("src/jvmTest/resources/okhttp3/internal/publicsuffix/PublicSuffixDatabase.gz")

  filteringCharset = Charsets.UTF_8.toString()

  val databaseGz = project.file("src/jvmTest/resources/okhttp3/internal/publicsuffix/PublicSuffixDatabase.gz")
  val listBytes = databaseGz.readBytes().toByteStringExpression()

  expand(
    // Build & use okhttp3/internal/-InternalVersion.kt
    "projectVersion" to project.version,

    // Build okhttp3/internal/publicsuffix/EmbeddedPublicSuffixList.kt
    "publicSuffixListBytes" to listBytes
  )
}

// Build & use okhttp3/internal/idn/IdnaMappingTableInstance.kt
val generateIdnaMappingTableConfiguration: Configuration by configurations.creating
dependencies {
  generateIdnaMappingTableConfiguration(projects.okhttpIdnaMappingTable)
}
val generateIdnaMappingTable = tasks.register<JavaExec>("generateIdnaMappingTable") {
  val idnaOutput = layout.buildDirectory.dir("generated/sources/idnaMappingTable")

  outputs.dir(idnaOutput)
  mainClass.set("okhttp3.internal.idn.GenerateIdnaMappingTableCode")
  args(idnaOutput.get())
  classpath = generateIdnaMappingTableConfiguration
}

kotlin {
  jvmToolchain(8)

  jvm {
//    withJava() /* <- cannot be used when the Android Plugin is present */
  }

  androidTarget {
    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_17)
    }
  }

  sourceSets {
    val commonJvmAndroid = create("commonJvmAndroid") {
      dependsOn(commonMain.get())

      kotlin.srcDir(copyKotlinTemplates.map { it.outputs })
      kotlin.srcDir(generateIdnaMappingTable.map { it.outputs })

      dependencies {
        api(libs.squareup.okio)
        api(libs.kotlin.stdlib)

        compileOnly(libs.findbugs.jsr305)
        compileOnly(libs.animalsniffer.annotations)
      }
    }

    androidMain {
      dependsOn(commonJvmAndroid)
      dependencies {
        compileOnly(libs.bouncycastle.bcprov)
        compileOnly(libs.bouncycastle.bctls)
        compileOnly(libs.conscrypt.openjdk)
      }
    }

    jvmMain {
      dependsOn(commonJvmAndroid)

      dependencies {
        // These compileOnly dependencies must also be listed in the OSGi configuration above.
        compileOnly(libs.conscrypt.openjdk)
        compileOnly(libs.bouncycastle.bcprov)
        compileOnly(libs.bouncycastle.bctls)

        // graal build support
        compileOnly(libs.nativeImageSvm)
        compileOnly(libs.openjsse)
      }
    }

    val jvmTest by getting {
      dependencies {
        implementation(projects.okhttpTestingSupport)
        implementation(libs.assertk)
        implementation(libs.kotlin.test.annotations)
        implementation(libs.kotlin.test.common)
        implementation(libs.kotlinx.serialization.core)
        implementation(libs.kotlinx.serialization.json)
        implementation(projects.okhttpJavaNetCookiejar)
        implementation(projects.okhttpTls)
        implementation(projects.okhttpUrlconnection)
        implementation(projects.mockwebserver3)
        implementation(projects.mockwebserver3Junit4)
        implementation(projects.mockwebserver3Junit5)
        implementation(projects.mockwebserver)
        implementation(projects.loggingInterceptor)
        implementation(projects.okhttpBrotli)
        implementation(projects.okhttpDnsoverhttps)
        implementation(projects.okhttpIdnaMappingTable)
        implementation(projects.okhttpSse)
        implementation(projects.okhttpCoroutines)
        implementation(libs.kotlinx.coroutines.core)
        implementation(libs.squareup.moshi)
        implementation(libs.squareup.moshi.kotlin)
        implementation(libs.squareup.okio.fakefilesystem)
        implementation(libs.conscrypt.openjdk)
        implementation(libs.junit)
        implementation(libs.junit.jupiter.api)
        implementation(libs.junit.jupiter.params)
        implementation(libs.kotlin.test.junit)
        implementation(libs.openjsse)
        compileOnly(libs.findbugs.jsr305)

        implementation(libs.junit.jupiter.engine)
        implementation(libs.junit.vintage.engine)

        if (platform == "conscrypt") {
          implementation(rootProject.libs.conscrypt.openjdk)
        } else if (platform == "openjsse") {
          implementation(rootProject.libs.openjsse)
        }
      }
    }
  }
}

if (platform == "jdk8alpn") {
  // Add alpn-boot on Java 8 so we can use HTTP/2 without a stable API.
  val alpnBootVersion = alpnBootVersion()
  if (alpnBootVersion != null) {
    val alpnBootJar = configurations.detachedConfiguration(
      dependencies.create("org.mortbay.jetty.alpn:alpn-boot:$alpnBootVersion")
    ).singleFile
    tasks.withType<Test> {
      jvmArgs("-Xbootclasspath/p:${alpnBootJar}")
    }
  }
}

android {
  compileSdk = 34

  namespace = "okhttp.okhttp3"

  defaultConfig {
    minSdk = 21

    consumerProguardFiles("okhttp3.pro")
  }
}

// Hack to make BundleTaskExtension pass briefly
project.extensions
  .getByType(JavaPluginExtension::class.java)
  .sourceSets.create("main")

// Call the convention when the task has finished, to modify the jar to contain OSGi metadata.
tasks.named<Jar>("jvmJar").configure {
  val bundleExtension = extensions.create(
    BundleTaskExtension.NAME,
    BundleTaskExtension::class.java,
    this,
  ).apply {
    classpath(libs.kotlin.stdlib.osgi.map { it.artifacts }, tasks.named("jvmMainClasses").map { it.outputs })
    bnd(
      "Export-Package: okhttp3,okhttp3.internal.*;okhttpinternal=true;mandatory:=okhttpinternal",
      "Import-Package: " +
        "com.oracle.svm.core.annotate;resolution:=optional," +
        "com.oracle.svm.core.configure;resolution:=optional," +
        "dalvik.system;resolution:=optional," +
        "org.conscrypt;resolution:=optional," +
        "org.bouncycastle.*;resolution:=optional," +
        "org.openjsse.*;resolution:=optional," +
        "org.graalvm.nativeimage;resolution:=optional," +
        "org.graalvm.nativeimage.hosted;resolution:=optional," +
        "sun.security.ssl;resolution:=optional,*",
      "Automatic-Module-Name: okhttp3",
      "Bundle-SymbolicName: com.squareup.okhttp3"
    )
  }

  doLast {
    bundleExtension.buildAction().execute(this)
  }
}

val checkstyleConfig: Configuration by configurations.named("checkstyleConfig")
dependencies {
  // Everything else requires Android API 21+.
  "signature"(rootProject.libs.signature.android.apilevel21) { artifact { type = "signature" } }

  // OkHttp requires Java 8+.
  "signature"(rootProject.libs.codehaus.signature.java18) { artifact { type = "signature" } }

  checkstyleConfig(rootProject.libs.checkStyle) {
    isTransitive = false
  }
}

// Animal Sniffer confirms we generally don't use APIs not on Java 8.
configure<AnimalSnifferExtension> {
  annotation = "okhttp3.internal.SuppressSignatureCheck"
  sourceSets = listOf(project.sourceSets["main"])
}

configure<CheckstyleExtension> {
  config = resources.text.fromArchiveEntry(checkstyleConfig, "google_checks.xml")
  toolVersion = rootProject.libs.versions.checkStyle.get()
  // TODO switch out checkstyle to use something supporting KMP
  sourceSets = listOf(project.sourceSets["main"])
}

apply(plugin = "io.github.usefulness.maven-sympathy")

mavenPublishing {
  configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty(), androidVariantsToPublish = listOf("release")))
}
