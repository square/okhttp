import com.android.build.gradle.internal.tasks.factory.dependsOn
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import me.champeau.gradle.japicmp.JapicmpTask
import org.jetbrains.kotlin.gradle.dsl.KotlinCompile

plugins {
  kotlin("multiplatform")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("binary-compatibility-validator")
  id("me.champeau.gradle.japicmp")
}

kotlin {
  jvm {
    withJava()
  }
  if (kmpJsEnabled) {
    js {
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
      browser {
      }
    }
  }

  sourceSets {
    commonMain {
      kotlin.srcDir("$buildDir/generated/sources/kotlinTemplates")
      dependencies {
        api(libs.squareup.okio)
      }
    }
    val commonTest by getting {
      dependencies {
        implementation(libs.kotlin.test.common)
        implementation(libs.kotlin.test.annotations)
        api(libs.assertk)
      }
    }
    val nonJvmMain = create("nonJvmMain") {
      dependencies {
        dependsOn(sourceSets.commonMain.get())
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
        implementation(projects.okhttpTestingSupport)
        implementation(projects.okhttpTls)
        implementation(projects.okhttpUrlconnection)
        implementation(projects.mockwebserver3)
        implementation(projects.mockwebserver3Junit4)
        implementation(projects.mockwebserver3Junit5)
        implementation(projects.mockwebserver)
        implementation(projects.loggingInterceptor)
        implementation(projects.okhttpBrotli)
        implementation(projects.okhttpDnsoverhttps)
        implementation(projects.okhttpSse)
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

tasks.register<JapicmpTask>("japicmp") {
  dependsOn("jvmJar")
  oldClasspath = files(project.baselineJar())
  newClasspath = files(tasks.getByName<Jar>("jvmJar").archiveFile)
  isOnlyBinaryIncompatibleModified = true
  isFailOnModification = true
  txtOutputFile = file("$buildDir/reports/japi.txt")
  isIgnoreMissingClasses = true
  isIncludeSynthetic = true
  packageExcludes = listOf(
    "okhttp3.internal",
    "okhttp3.internal.annotations",
    "okhttp3.internal.cache",
    "okhttp3.internal.cache2",
    "okhttp3.internal.connection",
    "okhttp3.internal.http",
    "okhttp3.internal.http1",
    "okhttp3.internal.http2",
    "okhttp3.internal.io",
    "okhttp3.internal.platform",
    "okhttp3.internal.proxy",
    "okhttp3.internal.publicsuffix",
    "okhttp3.internal.tls",
    "okhttp3.internal.ws",
  )
  classExcludes = listOf(
    // Package-private in 3.x, internal in 4.0.0:
    "okhttp3.Cache\$CacheResponseBody\$1",
    "okhttp3.RealCall\$AsyncCall",
  )
  methodExcludes = listOf(
    // Became "final" despite a non-final enclosing class in 4.0.0:
    "okhttp3.OkHttpClient#authenticator()",
    "okhttp3.OkHttpClient#cache()",
    "okhttp3.OkHttpClient#callTimeoutMillis()",
    "okhttp3.OkHttpClient#certificatePinner()",
    "okhttp3.OkHttpClient#connectionPool()",
    "okhttp3.OkHttpClient#connectionSpecs()",
    "okhttp3.OkHttpClient#connectTimeoutMillis()",
    "okhttp3.OkHttpClient#cookieJar()",
    "okhttp3.OkHttpClient#dispatcher()",
    "okhttp3.OkHttpClient#dns()",
    "okhttp3.OkHttpClient#eventListenerFactory()",
    "okhttp3.OkHttpClient#followRedirects()",
    "okhttp3.OkHttpClient#followSslRedirects()",
    "okhttp3.OkHttpClient#hostnameVerifier()",
    "okhttp3.OkHttpClient#interceptors()",
    "okhttp3.OkHttpClient#networkInterceptors()",
    "okhttp3.OkHttpClient#pingIntervalMillis()",
    "okhttp3.OkHttpClient#protocols()",
    "okhttp3.OkHttpClient#proxy()",
    "okhttp3.OkHttpClient#proxyAuthenticator()",
    "okhttp3.OkHttpClient#proxySelector()",
    "okhttp3.OkHttpClient#readTimeoutMillis()",
    "okhttp3.OkHttpClient#retryOnConnectionFailure()",
    "okhttp3.OkHttpClient#socketFactory()",
    "okhttp3.OkHttpClient#sslSocketFactory()",
    "okhttp3.OkHttpClient#writeTimeoutMillis()",
    "okhttp3.OkHttpClient#writeTimeoutMillis()",
    "okhttp3.Request\$Builder#delete()",
  )
}.let(tasks.check::dependsOn)

mavenPublishing {
  configure(KotlinMultiplatform(javadocJar = JavadocJar.Dokka("dokkaGfm")))
}

val copyKotlinTemplates = tasks.register<Copy>("copyKotlinTemplates") {
  from("src/commonMain/kotlinTemplates")
  into("$buildDir/generated/sources/kotlinTemplates")
  expand("projectVersion" to project.version)
  filteringCharset = Charsets.UTF_8.toString()
}
tasks.withType<KotlinCompile<*>> {
  dependsOn(copyKotlinTemplates)
}
