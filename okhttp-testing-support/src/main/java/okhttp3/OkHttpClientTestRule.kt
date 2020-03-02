/*
 * Copyright (C) 2019 Square, Inc.
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

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.testing.Flaky
import org.assertj.core.api.Assertions.assertThat
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Apply this rule to all tests. It adds additional checks for leaked resources and uncaught
 * exceptions.
 *
 * Use [newClient] as a factory for a OkHttpClient instances. These instances are specifically
 * configured for testing.
 */
class OkHttpClientTestRule : TestRule {
  private val clientEventsList = mutableListOf<String>()
  private var testClient: OkHttpClient? = null
  private var uncaughtException: Throwable? = null
  var logger: Logger? = null

  fun wrap(eventListener: EventListener) = object : EventListener.Factory {
    override fun create(call: Call) = ClientRuleEventListener(eventListener) { addEvent(it) }
  }

  fun wrap(eventListenerFactory: EventListener.Factory) = object : EventListener.Factory {
    override fun create(call: Call) = ClientRuleEventListener(eventListenerFactory.create(call)) { addEvent(it) }
  }

  /**
   * Returns an OkHttpClient for tests to use as a starting point.
   *
   * The returned client installs a default event listener that gathers debug information. This will
   * be logged if the test fails.
   *
   * This client is also configured to be slightly more deterministic, returning a single IP
   * address for all hosts, regardless of the actual number of IP addresses reported by DNS.
   */
  fun newClient(): OkHttpClient {
    var client = testClient
    if (client == null) {
      client = OkHttpClient.Builder()
          .dns(SINGLE_INET_ADDRESS_DNS) // Prevent unexpected fallback addresses.
          .eventListenerFactory(object : EventListener.Factory {
            override fun create(call: Call) = ClientRuleEventListener { addEvent(it) }
          })
          .build()
      testClient = client
    }
    return client
  }

  fun newClientBuilder(): OkHttpClient.Builder {
    return newClient().newBuilder()
  }

  @Synchronized private fun addEvent(event: String) {
    logger?.info(event)
    clientEventsList.add(event)
  }

  @Synchronized private fun initUncaughtException(throwable: Throwable) {
    if (uncaughtException == null) {
      uncaughtException = throwable
    }
  }

  fun ensureAllConnectionsReleased() {
    testClient?.let {
      val connectionPool = it.connectionPool
      connectionPool.evictAll()
      assertThat(connectionPool.connectionCount()).isEqualTo(0)
    }
  }

  private fun ensureAllTaskQueuesIdle() {
    for (queue in TaskRunner.INSTANCE.activeQueues()) {
      assertThat(queue.idleLatch().await(1_000L, TimeUnit.MILLISECONDS))
          .withFailMessage("Queue still active after 1000 ms")
          .isTrue()
    }
  }

  override fun apply(base: Statement, description: Description): Statement {
    return object : Statement() {
      override fun evaluate() {
        val defaultUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
          initUncaughtException(throwable)
        }
        try {
          base.evaluate()
          if (uncaughtException != null) {
            throw AssertionError("uncaught exception thrown during test", uncaughtException)
          }
          logEventsIfFlaky(description)
        } catch (t: Throwable) {
          logEvents()
          throw t
        } finally {
          Thread.setDefaultUncaughtExceptionHandler(defaultUncaughtExceptionHandler)
          ensureAllConnectionsReleased()
          releaseClient()
          ensureAllTaskQueuesIdle()
        }
      }

      private fun releaseClient() {
        testClient?.dispatcher?.executorService?.shutdown()
      }
    }
  }

  private fun logEventsIfFlaky(description: Description) {
    if (isTestFlaky(description)) {
      logEvents()
    }
  }

  private fun isTestFlaky(description: Description): Boolean {
    return description.annotations.any { it.annotationClass == Flaky::class } ||
        description.testClass.annotations.any { it.annotationClass == Flaky::class }
  }

  @Synchronized private fun logEvents() {
    // Will be ineffective if test overrides the listener
    println("Events (${clientEventsList.size})")

    for (e in clientEventsList) {
      println(e)
    }
  }

  companion object {
    /**
     * A network that resolves only one IP address per host. Use this when testing route selection
     * fallbacks to prevent the host machine's various IP addresses from interfering.
     */
    private val SINGLE_INET_ADDRESS_DNS = object : Dns {
      override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        return listOf(addresses[0])
      }
    }
  }
}
