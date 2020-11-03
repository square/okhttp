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
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestEngine
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.Launcher
import org.junit.platform.launcher.LauncherDiscoveryRequest
import org.junit.platform.launcher.PostDiscoveryFilter
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.core.EngineDiscoveryOrchestrator
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import java.io.File
import java.io.PrintWriter
import kotlin.system.exitProcess

/**
 * Graal main method to run tests with minimal reflection and automatic settings.
 * Uses the test list in native-image-tests/src/main/resources/testlist.txt.
 */
fun main(vararg args: String) {
  System.setProperty("junit.jupiter.extensions.autodetection.enabled", "true")

  val inputFile = if (args.isNotEmpty()) File(args[0]) else null
  val selectors = testSelectors(inputFile)

  val summaryListener = SummaryGeneratingListener()
  val treeListener = treeListener()

  val jupiterTestEngine = buildTestEngine()

  val config = LauncherConfig.builder()
    .enableTestExecutionListenerAutoRegistration(false)
    .enableTestEngineAutoRegistration(false)
    .enablePostDiscoveryFilterAutoRegistration(false)
    .addTestEngines(jupiterTestEngine)
    .addTestExecutionListeners(DotListener, summaryListener, treeListener)
    .build()
  val launcher: Launcher = LauncherFactory.create(config)

  val request: LauncherDiscoveryRequest = buildRequest(selectors)

  DotListener.install()

  try {
    launcher.execute(request)
  } finally {
    DotListener.uninstall()
  }

  val summary = summaryListener.summary
  summary.printTo(PrintWriter(System.out))

  exitProcess(if (summary.testsFailedCount != 0L) -1 else 0)
}

/**
 * Builds the Junit Test Engine for the native image.
 */
fun buildTestEngine(): TestEngine = JupiterTestEngine()

/**
 * Returns a fixed set of test classes from testlist.txt, skipping any not found in the
 * current classpath.  The IDE runs with less classes to avoid conflicting module ownership.
 */
fun testSelectors(inputFile: File? = null): List<DiscoverySelector> {
  val sampleTestClass = SampleTest::class.java

  val lines =
    inputFile?.readLines() ?: sampleTestClass.getResource("/testlist.txt").readText().lines()

  val flatClassnameList = lines
    .filter { it.isNotBlank() }

  return flatClassnameList
    .mapNotNull {
      try {
        selectClass(Class.forName(it, false, sampleTestClass.classLoader))
      } catch (cnfe: ClassNotFoundException) {
        println("Missing test class: $cnfe")
        null
      }
    }
}

/**
 * Builds a Junit Test Plan request for a fixed set of classes, or potentially a recursive package.
 */
fun buildRequest(selectors: List<DiscoverySelector>): LauncherDiscoveryRequest {
  val request: LauncherDiscoveryRequest = LauncherDiscoveryRequestBuilder.request()
    // TODO replace junit.jupiter.extensions.autodetection.enabled with API approach.
//    .enableImplicitConfigurationParameters(false)
    .selectors(selectors)
    .build()
  return request
}

/**
 * Flattens a test filter into a list of specific test descriptors, usually individual method in a
 * test class annotated with @Test.
 */
fun findTests(selectors: List<DiscoverySelector>): List<TestDescriptor> {
  val request: LauncherDiscoveryRequest = buildRequest(selectors)
  val testEngine = buildTestEngine()
  val filters = listOf<PostDiscoveryFilter>()
  val discoveryOrchestrator = EngineDiscoveryOrchestrator(listOf(testEngine), filters)
  val discovered = discoveryOrchestrator.discover(request, "run")

  return discovered.getEngineTestDescriptor(testEngine).descendants.toList()
}

/**
 * Builds the awkwardly package private TreePrintingListener listener which we would like to use
 * from ConsoleLauncher.
 *
 * https://github.com/junit-team/junit5/issues/2469
 */
fun treeListener(): TestExecutionListener {
  return Class.forName(
    "org.junit.platform.console.tasks.TreePrintingListener").declaredConstructors.first()
    .apply {
      isAccessible = true
    }
    .newInstance(PrintWriter(System.out), false, Theme.UNICODE) as TestExecutionListener
}
