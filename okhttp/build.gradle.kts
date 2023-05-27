import com.android.build.gradle.internal.tasks.factory.dependsOn
import com.android.build.gradle.tasks.JavaDocJarTask
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.dokka.gradle.DokkaTaskPartial
import org.jetbrains.kotlin.gradle.dsl.KotlinCompile

plugins {
  kotlin("multiplatform")
  kotlin("plugin.serialization")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("binary-compatibility-validator")
}

// Build & use okhttp3/internal/-InternalVersion.kt
val copyKotlinTemplates = tasks.register<Copy>("copyKotlinTemplates") {
  from("src/commonMain/kotlinTemplates")
  into("$buildDir/generated/sources/kotlinTemplates")
  expand("projectVersion" to project.version)
  filteringCharset = Charsets.UTF_8.toString()
}

// Build & use okhttp3/internal/idn/IdnaMappingTableInstance.kt
val generateIdnaMappingTableConfiguration: Configuration by configurations.creating
dependencies {
  generateIdnaMappingTableConfiguration(projects.okhttpIdnaMappingTable)
}
val generateIdnaMappingTable by tasks.creating(JavaExec::class.java) {
  outputs.dir("$buildDir/generated/sources/idnaMappingTable")
  mainClass.set("okhttp3.internal.idn.GenerateIdnaMappingTableCode")
  args("$buildDir/generated/sources/idnaMappingTable")
  classpath = generateIdnaMappingTableConfiguration
}

kotlin {
  jvm {
    withJava()
  }
  if (kmpJsEnabled) {
    js(IR) {
      compilations.all {
        kotlinOptions {
          moduleKind = "umd"
          sourceMap = true
          metaInfo = true
        }
      }
      nodejs {
        testTask {
          useMocha {
            timeout = "30s"
          }
        }
      }
    }
  }

  sourceSets {
    commonMain {
      kotlin.srcDir(copyKotlinTemplates.get().outputs)
      kotlin.srcDir(generateIdnaMappingTable.outputs)
      dependencies {
        api(libs.squareup.okio)
      }
    }
    val commonTest by getting {
      dependencies {
        implementation(projects.okhttpTestingSupport)
        implementation(libs.assertk)
        implementation(libs.kotlin.test.annotations)
        implementation(libs.kotlin.test.common)
        implementation(libs.kotlinx.serialization.core)
        implementation(libs.kotlinx.serialization.json)
      }
    }
    val nonJvmMain = create("nonJvmMain") {
      dependencies {
        dependsOn(sourceSets.commonMain.get())
        implementation(libs.kotlinx.coroutines.core)
        implementation(libs.squareup.okhttp.icu)
      }
    }
    val nonJvmTest = create("nonJvmTest") {
      dependencies {
        dependsOn(sourceSets.commonTest.get())
      }
    }

    getByName("jvmMain") {
      dependencies {
        api(libs.squareup.okio)
        api(libs.kotlin.stdlib)

        // These compileOnly dependencies must also be listed in the OSGi configuration above.
        compileOnly(libs.robolectric.android)
        compileOnly(libs.bouncycastle.bcprov)
        compileOnly(libs.bouncycastle.bctls)
        compileOnly(libs.conscrypt.openjdk)
        compileOnly(libs.openjsse)
        compileOnly(libs.findbugs.jsr305)
        compileOnly(libs.animalsniffer.annotations)

        // graal build support
        compileOnly(libs.nativeImageSvm)
      }
    }
    getByName("jvmTest") {
      dependencies {
        dependsOn(commonTest)
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
        implementation(libs.assertj.core)
        implementation(libs.openjsse)
        implementation(libs.aqute.resolve)
        compileOnly(libs.findbugs.jsr305)
      }

      getByName("jsMain") {
        dependencies {
          dependsOn(nonJvmMain)
          api(libs.squareup.okio)
          api(libs.kotlin.stdlib)
        }
      }

      getByName("jsTest") {
        dependencies {
          dependsOn(nonJvmTest)
          implementation(libs.kotlin.test.js)
          implementation(libs.kotlinx.coroutines.test)
        }
      }
    }
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

val copyOsgiTestDeployment by tasks.creating(Copy::class.java) {
  from(osgiTestDeploy)
  into("$buildDir/resources/jvmTest/okhttp3/osgi/deployments")
}
tasks.getByName("jvmTest") {
  dependsOn(copyOsgiTestDeployment)
}

dependencies {
  osgiTestDeploy(libs.eclipseOsgi)
  osgiTestDeploy(libs.kotlin.stdlib.osgi)
}

mavenPublishing {
  configure(KotlinMultiplatform(javadocJar = JavadocJar.Dokka("dokkaGfm")))
}
