plugins {
  id("org.graalvm.buildtools.native")
  kotlin("jvm")
}

animalsniffer {
  isIgnoreFailures = true
}

// TODO reenable other tests
// https://github.com/square/okhttp/issues/8901
//sourceSets {
//  test {
//    java.srcDirs(
//      "../okhttp-brotli/src/test/java",
//      "../okhttp-dnsoverhttps/src/test/java",
//      "../okhttp-logging-interceptor/src/test/java",
//      "../okhttp-sse/src/test/java",
//    )
//  }
//}

dependencies {
  implementation(projects.okhttp)

  testImplementation(projects.mockwebserver3Junit5)
  testImplementation(libs.assertk)
  testImplementation(kotlin("test"))
}

graalvmNative {
  testSupport = true

  binaries {
    named("test") {
      // speed up development testing
      buildArgs.add("--strict-image-heap")
      // see https://github.com/junit-team/junit5/wiki/Upgrading-to-JUnit-5.13
      // should not be needed after updating native build tools to 0.11.0
      val initializeAtBuildTime = listOf(
        "kotlin.coroutines.intrinsics.CoroutineSingletons",
        "org.junit.jupiter.api.DisplayNameGenerator\$IndicativeSentences",
        "org.junit.jupiter.engine.descriptor.ClassBasedTestDescriptor\$ClassInfo",
        "org.junit.jupiter.engine.descriptor.ClassBasedTestDescriptor\$LifecycleMethods",
        "org.junit.jupiter.engine.descriptor.ClassTemplateInvocationTestDescriptor",
        "org.junit.jupiter.engine.descriptor.ClassTemplateTestDescriptor",
        "org.junit.jupiter.engine.descriptor.DynamicDescendantFilter\$Mode",
        "org.junit.jupiter.engine.descriptor.ExclusiveResourceCollector\$1",
        "org.junit.jupiter.engine.descriptor.MethodBasedTestDescriptor\$MethodInfo",
        "org.junit.jupiter.engine.config.InstantiatingConfigurationParameterConverter",
        "org.junit.jupiter.engine.discovery.ClassSelectorResolver\$DummyClassTemplateInvocationContext",
        "org.junit.platform.engine.support.store.NamespacedHierarchicalStore\$EvaluatedValue",
        "org.junit.platform.launcher.core.DiscoveryIssueNotifier",
        "org.junit.platform.launcher.core.HierarchicalOutputDirectoryProvider",
        "org.junit.platform.launcher.core.LauncherConfig",
        "org.junit.platform.launcher.core.LauncherPhase",
        "org.junit.platform.launcher.core.LauncherDiscoveryResult\$EngineResultInfo",
        "org.junit.platform.suite.engine.SuiteTestDescriptor\$LifecycleMethods"
      )
      buildArgs.add("--initialize-at-build-time=${initializeAtBuildTime.joinToString(",")}")
      buildArgs.add("--trace-class-initialization=kotlin.annotation.AnnotationTarget,org.junit.platform.launcher.core.DiscoveryIssueNotifier,org.junit.platform.launcher.core.LauncherPhase,org.junit.platform.launcher.core.HierarchicalOutputDirectoryProvider,kotlin.annotation.AnnotationRetention,org.junit.platform.engine.support.store.NamespacedHierarchicalStore\$EvaluatedValue")
      buildArgs.add("-Ob")
    }
  }
}
