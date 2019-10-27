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

import okhttp3.internal.concurrent.TaskRunner
import okhttp3.testing.Flaky
import org.assertj.core.api.Assertions.assertThat
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Apply this rule to tests that need an OkHttpClient instance. */
class OkHttpClientTestRule : TestRule {
  private val clientEventsList = mutableListOf<String>()
  private var testClient: OkHttpClient? = null

  fun wrap(eventListener: EventListener) = object : EventListener.Factory {
    override fun create(call: Call): EventListener = ClientRuleEventListener(eventListener) { addEvent(it) }
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
            override fun create(call: Call): EventListener = ClientRuleEventListener { addEvent(it) }
          })
          .build()
      testClient = client
    }
    return client
  }

  fun newClientBuilder(): OkHttpClient.Builder {
    return newClient().newBuilder()
  }

  @Synchronized private fun addEvent(it: String) {
    clientEventsList.add(it)
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
      assertThat(queue.awaitIdle(TimeUnit.MILLISECONDS.toNanos(1000L)))
          .withFailMessage("Queue still active after 1000ms")
          .isTrue()
    }
  }

  override fun apply(base: Statement, description: Description): Statement {
    return object : Statement() {
      override fun evaluate() {
        try {
          base.evaluate()
          logEventsIfFlaky(description)
        } catch (t: Throwable) {
          logEvents()
          throw t
        } finally {
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
