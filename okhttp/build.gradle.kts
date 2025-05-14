@file:Suppress("UnstableApiUsage")

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import ru.vyarus.gradle.plugin.animalsniffer.AnimalSniffer
import ru.vyarus.gradle.plugin.animalsniffer.AnimalSnifferExtension

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

val copyKotlinTemplates = tasks.register<Copy>("copyKotlinTemplates") {
  val kotlinTemplatesOutput = layout.buildDirectory.dir("generated/sources/kotlinTemplates")

  from("src/commonJvmAndroid/kotlinTemplates")
  into(kotlinTemplatesOutput)

  filteringCharset = Charsets.UTF_8.toString()

  expand(
    // Build & use okhttp3/internal/-InternalVersion.kt
    "projectVersion" to project.version,
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

        compileOnly(libs.animalsniffer.annotations)
      }
    }

    commonTest {
      dependencies {
        implementation(projects.okhttpTestingSupport)
        implementation(libs.assertk)
        implementation(libs.kotlin.test.annotations)
        implementation(libs.kotlin.test.common)
        implementation(libs.kotlin.test.junit)
        implementation(libs.junit)
        implementation(libs.junit.jupiter.api)
        implementation(libs.junit.jupiter.params)
      }
    }

    androidMain {
      dependsOn(commonJvmAndroid)
      dependencies {
        compileOnly(libs.bouncycastle.bcprov)
        compileOnly(libs.bouncycastle.bctls)
        compileOnly(libs.conscrypt.openjdk)
        implementation(libs.androidx.annotation)
        implementation(libs.androidx.startup.runtime)
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

        implementation(libs.junit.jupiter.engine)
        implementation(libs.junit.vintage.engine)

        if (platform == "conscrypt") {
          implementation(rootProject.libs.conscrypt.openjdk)
        } else if (platform == "openjsse") {
          implementation(rootProject.libs.openjsse)
        }
      }
    }

    val androidUnitTest by getting {
      dependencies {
        implementation(libs.assertk)
        implementation(libs.kotlin.test.annotations)
        implementation(libs.kotlin.test.common)
        implementation(libs.androidx.junit)

        implementation(libs.junit.jupiter.engine)
        implementation(libs.junit.vintage.engine)

        implementation(libs.robolectric)
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
  compileSdk = 35

  namespace = "okhttp.okhttp3"

  defaultConfig {
    minSdk = 21

    consumerProguardFiles("okhttp3.pro")
  }

  testOptions {
    unitTests {
      isIncludeAndroidResources = true
    }
  }

  sourceSets {
    named("main") {
      manifest.srcFile("src/androidMain/AndroidManifest.xml")
      assets.srcDir("src/androidMain/assets")
    }
  }
}

// Call the convention when the task has finished, to modify the jar to contain OSGi metadata.
tasks.named<Jar>("jvmJar").configure {
  // Disable to unblock Kotlin bump
  // Raised https://github.com/bndtools/bnd/issues/6590

  manifest {
    attributes(
      "Automatic-Module-Name" to "okhttp3",
      "Bundle-ManifestVersion" to "okhttp3",
      "Bundle-Name" to "com.squareup.okhttp3",
      "Bundle-SymbolicName" to "com.squareup.okhttp3",
      "Bundle-Version" to "5.0.0",
      "Require-Capability" to "osgi.ee;filter:=\"(&(osgi.ee=JavaSE)(version=1.8))",
      "Export-Package" to """okhttp3;uses:="javax.net,javax.net.ssl,kotlin,kotlin.annotation,kotlin.enums,kotlin.jvm,kotlin.jvm.functions,kotlin.jvm.internal,kotlin.jvm.internal.markers,kotlin.reflect,okhttp3.internal.cache,okhttp3.internal.concurrent,okhttp3.internal.connection,okhttp3.internal.tls,okio";version="5.0.0",okhttp3.internal;okhttpinternal=true;mandatory:=okhttpinternal;uses:="javax.net.ssl,kotlin,kotlin.annotation,kotlin.jvm.functions,kotlin.reflect,okhttp3,okhttp3.internal.concurrent,okhttp3.internal.connection,okhttp3.internal.http2,okio";version="5.0.0",okhttp3.internal.authenticator;okhttpinternal=true;mandatory:=okhttpinternal;uses:="kotlin,kotlin.jvm.internal,okhttp3";version="5.0.0",okhttp3.internal.cache;okhttpinternal=true;mandatory:=okhttpinternal;uses:="kotlin,kotlin.jvm.functions,kotlin.jvm.internal,kotlin.jvm.internal.markers,kotlin.text,okhttp3,okhttp3.internal.concurrent,okio";version="5.0.0",okhttp3.internal.cache2;okhttpinternal=true;mandatory:=okhttpinternal;uses:="kotlin,kotlin.jvm.internal,okio";version="5.0.0",okhttp3.internal.concurrent;okhttpinternal=true;mandatory:=okhttpinternal;uses:="kotlin,kotlin.jvm.functions,kotlin.jvm.internal";version="5.0.0",okhttp3.internal.connection;okhttpinternal=true;mandatory:=okhttpinternal;uses:="javax.net.ssl,kotlin,kotlin.collections,kotlin.jvm.functions,kotlin.jvm.internal,okhttp3,okhttp3.internal.concurrent,okhttp3.internal.http,okhttp3.internal.http2,okhttp3.internal.ws,okio";version="5.0.0",okhttp3.internal.graal;okhttpinternal=true;mandatory:=okhttpinternal;uses:="com.oracle.svm.core.annotate,kotlin,okhttp3.internal.platform,org.graalvm.nativeimage.hosted";version="5.0.0",okhttp3.internal.http;okhttpinternal=true;mandatory:=okhttpinternal;uses:="kotlin,kotlin.jvm,kotlin.jvm.internal,okhttp3,okhttp3.internal.connection,okio";version="5.0.0",okhttp3.internal.http1;okhttpinternal=true;mandatory:=okhttpinternal;uses:="kotlin,kotlin.jvm.internal,okhttp3,okhttp3.internal.http,okio";version="5.0.0",okhttp3.internal.http2;okhttpinternal=true;mandatory:=okhttpinternal;uses:="kotlin,kotlin.enums,kotlin.jvm.functions,kotlin.jvm.internal,okhttp3,okhttp3.internal.concurrent,okhttp3.internal.http,okhttp3.internal.http2.flowcontrol,okio";version="5.0.0",okhttp3.internal.http2.flowcontrol;okhttpinternal=true;mandatory:=okhttpinternal;uses:=kotlin;version="5.0.0",okhttp3.internal.idn;okhttpinternal=true;mandatory:=okhttpinternal;uses:="kotlin,kotlin.jvm.functions,okio";version="5.0.0",okhttp3.internal.platform;okhttpinternal=true;mandatory:=okhttpinternal;uses:="javax.net.ssl,kotlin,kotlin.jvm,kotlin.jvm.internal,okhttp3,okhttp3.internal.tls,org.conscrypt";version="5.0.0",okhttp3.internal.proxy;okhttpinternal=true;mandatory:=okhttpinternal;uses:=kotlin;version="5.0.0",okhttp3.internal.publicsuffix;okhttpinternal=true;mandatory:=okhttpinternal;uses:="kotlin,kotlin.jvm.internal,okio";version="5.0.0",okhttp3.internal.tls;okhttpinternal=true;mandatory:=okhttpinternal;uses:="javax.net.ssl,kotlin,kotlin.jvm.internal";version="5.0.0",okhttp3.internal.url;okhttpinternal=true;mandatory:=okhttpinternal;uses:="kotlin,okio";version="5.0.0",okhttp3.internal.ws;okhttpinternal=true;mandatory:=okhttpinternal;uses:="kotlin,kotlin.jvm.internal,okhttp3,okhttp3.internal.concurrent,okhttp3.internal.connection,okio";version="5.0.0"""",
      "Import-Package" to """com.oracle.svm.core.annotate;resolution:=optional,org.conscrypt;resolution:=optional;version="[2.5,3)",org.bouncycastle.jsse;resolution:=optional;version="[1.80,2)",org.bouncycastle.jsse.provider;resolution:=optional;version="[1.80,2)",org.openjsse.javax.net.ssl;resolution:=optional,org.openjsse.net.ssl;resolution:=optional,org.graalvm.nativeimage.hosted;resolution:=optional,sun.security.ssl;resolution:=optional,java.io,java.lang,java.lang.annotation,java.lang.invoke,java.lang.ref,java.lang.reflect,java.net,java.nio.channels,java.nio.charset,java.security,java.security.cert,java.text,java.time,java.util,java.util.concurrent,java.util.concurrent.atomic,java.util.concurrent.locks,java.util.logging,java.util.regex,java.util.zip,javax.net,javax.net.ssl,javax.security.auth.x500,kotlin,kotlin.annotation,kotlin.collections,kotlin.comparisons,kotlin.enums,kotlin.internal,kotlin.io,kotlin.jvm,kotlin.jvm.functions,kotlin.jvm.internal,kotlin.jvm.internal.markers,kotlin.ranges,kotlin.reflect,kotlin.sequences,kotlin.text,kotlin.time,okio,com.oracle.svm.core.configure;resolution:=optional,dalvik.system;resolution:=optional,org.graalvm.nativeimage;resolution:=optional""",
    )
  }
}

val androidSignature by configurations.getting
val jvmSignature by configurations.getting

val checkstyleConfig: Configuration by configurations.named("checkstyleConfig")
dependencies {
  // Everything else requires Android API 21+.
  androidSignature(rootProject.libs.signature.android.apilevel21) { artifact { type = "signature" } }

  // OkHttp requires Java 8+.
  jvmSignature(rootProject.libs.codehaus.signature.java18) { artifact { type = "signature" } }

  checkstyleConfig(rootProject.libs.checkStyle) {
    isTransitive = false
  }
}

// Animal Sniffer confirms we generally don't use APIs not on Java 8.
configure<AnimalSnifferExtension> {
  annotation = "okhttp3.internal.SuppressSignatureCheck"
  defaultTargets("jvmMain", "debug")
}

project.tasks.withType<AnimalSniffer> {
  if (targetName == "animalsnifferJvmMain") {
    animalsnifferSignatures = jvmSignature
  } else {
    animalsnifferSignatures = androidSignature
  }
}

configure<CheckstyleExtension> {
  config = resources.text.fromArchiveEntry(checkstyleConfig, "google_checks.xml")
  toolVersion = rootProject.libs.versions.checkStyle.get()
  sourceSets = listOf(project.sourceSets["jvmMain"])
}

afterEvaluate {
  tasks.withType<Test> {
    if (javaLauncher.get().metadata.languageVersion.asInt() < 9) {
      // Work around robolectric requirements and limitations
      // https://cs.android.com/android-studio/platform/tools/base/+/mirror-goog-studio-main:build-system/gradle-core/src/main/java/com/android/build/gradle/tasks/factory/AndroidUnitTest.java;l=339
      allJvmArgs = allJvmArgs.filter { !it.startsWith("--add-opens") }
      filter {
        excludeTest("okhttp3.internal.publicsuffix.PublicSuffixDatabaseTest", null)
      }
    }
  }
}

apply(plugin = "io.github.usefulness.maven-sympathy")

mavenPublishing {
  configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty(), androidVariantsToPublish = listOf("release")))
}
