import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  kotlin("jvm")
  kotlin("plugin.serialization")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("binary-compatibility-validator")
}

// Build & use okhttp3/internal/-InternalVersion.kt
val copyKotlinTemplates = tasks.register<Copy>("copyKotlinTemplates") {
  from("src/main/kotlinTemplates")
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
  sourceSets {
    getByName("main") {
      kotlin.srcDir(copyKotlinTemplates.get().outputs)
      kotlin.srcDir(generateIdnaMappingTable.outputs)
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
  into("$buildDir/resources/test/okhttp3/osgi/deployments")
}
tasks.getByName("test") {
  dependsOn(copyOsgiTestDeployment)
}

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

  testCompileOnly(libs.bouncycastle.bctls)
  testImplementation(projects.okhttpTestingSupport)
  testImplementation(libs.assertk)
  testImplementation(libs.kotlin.test.annotations)
  testImplementation(libs.kotlin.test.common)
  testImplementation(libs.kotlinx.serialization.core)
  testImplementation(libs.kotlinx.serialization.json)
  testImplementation(projects.okhttpJavaNetCookiejar)
  testImplementation(projects.okhttpTls)
  testImplementation(projects.okhttpUrlconnection)
  testImplementation(projects.mockwebserver3)
  testImplementation(projects.mockwebserver3Junit4)
  testImplementation(projects.mockwebserver3Junit5)
  testImplementation(projects.mockwebserver)
  testImplementation(projects.loggingInterceptor)
  testImplementation(projects.okhttpBrotli)
  testImplementation(projects.okhttpDnsoverhttps)
  testImplementation(projects.okhttpIdnaMappingTable)
  testImplementation(projects.okhttpSse)
  testImplementation(projects.okhttpCoroutines)
  testImplementation(libs.kotlinx.coroutines.core)
  testImplementation(libs.squareup.moshi)
  testImplementation(libs.squareup.moshi.kotlin)
  testImplementation(libs.squareup.okio.fakefilesystem)
  testImplementation(libs.conscrypt.openjdk)
  testImplementation(libs.junit)
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.params)
  testImplementation(libs.kotlin.test.junit)
  testImplementation(libs.openjsse)
  testImplementation(libs.aqute.resolve)
  testCompileOnly(libs.findbugs.jsr305)

  osgiTestDeploy(libs.eclipseOsgi)
  osgiTestDeploy(libs.kotlin.stdlib.osgi)
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Empty()))
}
