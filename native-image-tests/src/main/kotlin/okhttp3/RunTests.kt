/*
 * Copyright (C) 2020 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3

import org.junit.jupiter.engine.JupiterTestEngine
import org.junit.platform.console.options.Theme
import org.junit.platform.engine.TestEngine
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.Launcher
import org.junit.platform.launcher.LauncherDiscoveryRequest
import org.junit.platform.launcher.PostDiscoveryFilter
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import java.io.PrintWriter
import kotlin.system.exitProcess

val knownTests = listOf(okhttp3.sse.internal.EventSourceHttpTest::class.java,
  okhttp3.logging.IsProbablyUtf8Test::class.java,
  okhttp3.logging.LoggingEventListenerTest::class.java,
  okhttp3.logging.HttpLoggingInterceptorTest::class.java,
  okhttp3.sse.internal.ServerSentEventIteratorTest::class.java,
  okhttp3.CallTest::class.java,
  SampleTest::class.java)

fun main() {
  System.setProperty("junit.jupiter.extensions.autodetection.enabled", "true")

  val config2 = LauncherConfig.builder()
    .enableTestExecutionListenerAutoRegistration(false)
    .enableTestEngineAutoRegistration(false)
    .enablePostDiscoveryFilterAutoRegistration(false)
    .addTestEngines(JupiterTestEngine())
    .build()
  val launcher: Launcher = LauncherFactory.create(config2)

  val request: LauncherDiscoveryRequest = LauncherDiscoveryRequestBuilder.request()
    .selectors(knownTests.map { selectClass(it) })
    .build()

  val summaryListener = SummaryGeneratingListener()
  val treeListener = treeListener()
  launcher.registerTestExecutionListeners(summaryListener, treeListener)

  val result = launcher.execute(request)

  val summary = summaryListener.summary
  summary.printTo(PrintWriter(System.out))

  exitProcess(if (summary.testsFailedCount != 0L) -1 else 0)
}

// https://github.com/junit-team/junit5/issues/2469
private fun treeListener(): TestExecutionListener {
  return Class.forName(
    "org.junit.platform.console.tasks.TreePrintingListener").declaredConstructors.first()
    .apply {
      println(this)
      isAccessible = true
    }
    .newInstance(PrintWriter(System.out), false, Theme.UNICODE) as TestExecutionListener
}
