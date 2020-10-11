package okhttp3.test.graal

import com.oracle.svm.core.annotate.AutomaticFeature
import org.graalvm.nativeimage.hosted.Feature
import org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.Launcher
import org.junit.platform.launcher.TestPlan
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory

@AutomaticFeature
class JunitTestRegisterer: Feature {
  override fun beforeAnalysis(access: Feature.BeforeAnalysisAccess?) {
//    discoverTests()
  }

  fun discoverTests() {
    val request = LauncherDiscoveryRequestBuilder.request()
      .selectors(
        selectPackage("okhttp3")
      )
  //      .filters(
  //        includeClassNamePatterns(".*Tests")
  //      )
        .build()

    val launcher: Launcher = LauncherFactory.create()

    val testPlan: TestPlan = launcher.discover(request)

    testPlan.roots.forEach {
      val x = testPlan.getDescendants(it)

      x.forEach {
        it.source.ifPresent { source ->
          if (source is ClassSource) {
            println(source.className)
//            RuntimeReflection.register(source.javaClass)
          } else if (source is MethodSource) {
            println(source.methodName)
//            RuntimeReflection.register(source.javaMethod)
          }
        }
      }
    }
  }
}

