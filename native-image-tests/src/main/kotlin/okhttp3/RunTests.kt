package okhttp3

import org.junit.jupiter.engine.JupiterTestEngine
import org.junit.platform.console.ConsoleLauncher
import org.junit.platform.console.options.Details
import org.junit.platform.engine.ConfigurationParameters
import org.junit.platform.engine.DiscoveryFilter
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.engine.TestEngine
import org.junit.platform.engine.discovery.ClassSelector
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.launcher.EngineFilter
import org.junit.platform.launcher.Launcher
import org.junit.platform.launcher.LauncherDiscoveryRequest
import org.junit.platform.launcher.PostDiscoveryFilter
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import java.io.PrintWriter
import java.util.Optional

fun main() {
  System.setProperty("junit.jupiter.extensions.autodetection.enabled", "true")

  val tests = listOf("okhttp3.sse.internal.EventSourceHttpTest",
    "okhttp3.logging.IsProbablyUtf8Test",
    "okhttp3.logging.LoggingEventListenerTest",
    "okhttp3.logging.HttpLoggingInterceptorTest",
    "okhttp3.sse.internal.ServerSentEventIteratorTest")

//  ConsoleLauncher.main(*tests.flatMap { listOf("-c", it) }.toTypedArray())
  val config = object : LauncherConfig {
    override fun isTestEngineAutoRegistrationEnabled(): Boolean = false

    override fun isTestExecutionListenerAutoRegistrationEnabled(): Boolean = false

    override fun isPostDiscoveryFilterAutoRegistrationEnabled(): Boolean = false

    override fun getAdditionalTestEngines(): MutableCollection<TestEngine> = mutableListOf(
      JupiterTestEngine())

    override fun getAdditionalTestExecutionListeners(): MutableCollection<TestExecutionListener> = mutableListOf()

    override fun getAdditionalPostDiscoveryFilters(): MutableCollection<PostDiscoveryFilter> = mutableListOf()
  }

  val launcher: Launcher = LauncherFactory.create(config)

  val launcherDiscoveryRequest = object: LauncherDiscoveryRequest {
    override fun <T : DiscoverySelector?> getSelectorsByType(selectorType: Class<T>?): MutableList<T> {
      if (selectorType == ClassSelector::getJavaClass) {
        return tests.map { DiscoverySelectors.selectClass(it) as T }.toMutableList()
      } else {
        return mutableListOf()
      }
    }

    override fun <T : DiscoveryFilter<*>?> getFiltersByType(filterType: Class<T>?): MutableList<T> =
      mutableListOf()

    override fun getConfigurationParameters(): ConfigurationParameters {
      return object : ConfigurationParameters {
        override fun get(key: String?): Optional<String> {
          return Optional.empty()
        }

        override fun getBoolean(key: String?): Optional<Boolean> {
          return Optional.empty()
        }

        override fun size(): Int {
          return 0
        }

      }
    }

    override fun getEngineFilters(): MutableList<EngineFilter> = mutableListOf()

    override fun getPostDiscoveryFilters(): MutableList<PostDiscoveryFilter> = mutableListOf()
  }

  val summaryListener = SummaryGeneratingListener()
  launcher.registerTestExecutionListeners(summaryListener)

  launcher.execute(launcherDiscoveryRequest)

  val summary = summaryListener.summary
  summary.printTo(PrintWriter(System.out))
}
