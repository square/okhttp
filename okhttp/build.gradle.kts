import com.android.build.gradle.internal.tasks.factory.dependsOn
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import java.util.Base64

plugins {
  kotlin("multiplatform")
  id("com.android.library")
  kotlin("plugin.serialization")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("binary-compatibility-validator")
}

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
  }

  androidTarget {
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

    val androidUnitTest by getting {
      dependencies {
        implementation(projects.okhttpTestingSupport)
        implementation(libs.assertk)
        implementation(libs.kotlin.test.annotations)
        implementation(libs.kotlin.test.common)

        implementation(libs.robolectric)
        implementation(libs.junit)

        implementation(libs.junit.jupiter.engine)
        implementation(libs.junit.vintage.engine)
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
        implementation(libs.aqute.resolve)
        compileOnly(libs.findbugs.jsr305)

        implementation(libs.junit.jupiter.engine)
        implementation(libs.junit.vintage.engine)
      }
    }
  }
}

android {
  compileSdk = 34

  namespace = "okhttp.okhttp3"

  defaultConfig {
    minSdk = 21
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
}

project.applyOsgi(
  "Export-Package: okhttp3,okhttp3.internal.*;okhttpinternal=true;mandatory:=okhttpinternal",
  "Import-Package: " +
    "android.*;resolution:=optional," +
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

normalization {
  runtimeClasspath {
    /*
       - The below two ignored files are generated during test execution
       by the test: okhttp/src/test/java/okhttp3/osgi/OsgiTest.java

       - The compressed index.xml file contains a timestamp property which
       changes with every test execution, such that running the test
       actually changes the test classpath itself. This means that it
       can"t benefit from incremental build acceleration, because on every
       execution it sees that the classpath has changed, and so to be
       safe, it needs to re-run.

       - This is unfortunate, because actually it would be safe to declare
       the task as up-to-date, because these two files, which are based on
       the generated index.xml, are outputs, not inputs. We can be sure of
       this because they are deleted in the @BeforeEach method of the
       OsgiTest test class.

       - To enable the benefit of incremental builds, we can ask Gradle
       to ignore these two files when considering whether the classpath
       has changed. That is the purpose of this normalization block.
   */
    ignore("okhttp3/osgi/workspace/cnf/repo/index.xml.gz")
    ignore("okhttp3/osgi/workspace/cnf/repo/index.xml.gz.sha")
  }
}

// Expose OSGi jars to the test environment.
val osgiTestDeploy: Configuration by configurations.creating

val jvmTest = tasks.named("jvmTest")
val copyOsgiTestDeployment = tasks.register<Copy>("copyOsgiTestDeployment") {
  from(osgiTestDeploy)
  into(layout.buildDirectory.dir("resources/jvmTest/okhttp3/osgi/deployments"))

}
jvmTest.dependsOn(copyOsgiTestDeployment)

dependencies {
  osgiTestDeploy(libs.eclipseOsgi)
  osgiTestDeploy(libs.kotlin.stdlib.osgi)
}

mavenPublishing {
  configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty()))
}
