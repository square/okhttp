plugins {
  kotlin("jvm")
}

dependencies {
  implementation(projects.okhttp)
  implementation(projects.okhttpBrotli)
  implementation(projects.okhttpCoroutines)
  implementation(projects.okhttpDnsoverhttps)
  implementation(projects.loggingInterceptor)
  implementation(projects.okhttpSse)
  implementation(projects.okhttpTls)
  implementation(projects.okhttpUrlconnection)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(libs.junit)
  testImplementation(libs.kotlin.test.common)
  testImplementation(libs.kotlin.test.junit)
  testImplementation(libs.assertk)

  testImplementation(libs.aqute.resolve)
}

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

val test = tasks.named("test")
val copyOsgiTestDeployment = tasks.register<Copy>("copyOsgiTestDeployment") {
  from(osgiTestDeploy)
  into(layout.buildDirectory.dir("resources/test/okhttp3/osgi/deployments"))
}

test.configure {
  dependsOn(copyOsgiTestDeployment)
}

dependencies {
  osgiTestDeploy(libs.eclipseOsgi)
  osgiTestDeploy(libs.kotlin.stdlib.osgi)
}

val testJavaVersion = System.getProperty("test.java.version", "21").toInt()
tasks.withType<Test> {
  onlyIf("Tests require JDK 17") {
    testJavaVersion > 17
  }
}
