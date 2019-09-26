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

import okhttp3.internal.concurrent.Task
import okhttp3.internal.concurrent.TaskQueue
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.testing.Flaky
import org.assertj.core.api.Assertions.assertThat
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Apply this rule to tests that need an OkHttpClient instance. */
class OkHttpClientTestRule : TestRule {
  private val clientEventsList = mutableListOf<String>()
  private var prototype: OkHttpClient? = null

  /**
   * Returns an OkHttpClient for all tests to use as a starting point.
   *
   * The shared instance allows all tests to share a single connection pool, which prevents idle
   * connections from consuming unnecessary resources while connections wait to be evicted.
   *
   * This client is also configured to be slightly more deterministic, returning a single IP
   * address for all hosts, regardless of the actual number of IP addresses reported by DNS.
   */
  fun newClient(): OkHttpClient {
    return newClientBuilder().build()
  }

  fun newClientBuilder(): OkHttpClient.Builder {
    return checkNotNull(prototype) { "don't create clients in test initialization!" }
        .newBuilder()
        .eventListener(ClientRuleEventListener { addEvent(it) })
  }

  @Synchronized private fun addEvent(it: String) {
    clientEventsList.add(it)
  }

  fun ensureAllConnectionsReleased() {
    prototype?.let {
      val connectionPool = it.connectionPool
      connectionPool.evictAll()
      assertThat(connectionPool.connectionCount()).isEqualTo(0)
    }
  }

  private fun ensureAllTaskQueuesIdle() {
    for (queue in TaskRunner.INSTANCE.activeQueues()) {
      assertThat(queue.awaitIdle(500L, TimeUnit.MILLISECONDS))
          .withFailMessage("Queue ${queue.owner} still active after 500ms")
          .isTrue()
    }
  }

  override fun apply(base: Statement, description: Description): Statement {
    return object : Statement() {
      override fun evaluate() {
        acquireClient()
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

      private fun acquireClient() {
        prototype = prototypes.poll() ?: freshClient()
      }

      private fun releaseClient() {
        prototype?.let {
          prototypes.push(it)
          prototype = null
        }
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

  /**
   * Called if a test is known to be leaky.
   */
  fun abandonClient() {
    prototype?.let {
      prototype = null
      it.dispatcher.executorService.shutdownNow()
      it.connectionPool.evictAll()
    }
  }

  /** Returns true if this queue became idle before the timeout elapsed. */
  private fun TaskQueue.awaitIdle(timeout: Long, timeUnit: TimeUnit): Boolean {
    val latch = CountDownLatch(1)
    schedule(object : Task("awaitIdle") {
      override fun runOnce(): Long {
        latch.countDown()
        return -1L
      }
    })

    return latch.await(timeout, timeUnit)
  }

  companion object {
    /**
     * Quick and dirty pool of OkHttpClient instances. Each has its own independent dispatcher and
     * connection pool. This way we can reuse expensive resources while preventing concurrent tests
     * from interfering with each other.
     */
    internal val prototypes = ConcurrentLinkedDeque<OkHttpClient>()

    /**
     * A network that resolves only one IP address per host. Use this when testing route selection
     * fallbacks to prevent the host machine's various IP addresses from interfering.
     */
    internal val SINGLE_INET_ADDRESS_DNS = object : Dns {
      override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        return listOf(addresses[0])
      }
    }

    private fun freshClient(): OkHttpClient {
      return OkHttpClient.Builder()
          .dns(SINGLE_INET_ADDRESS_DNS) // Prevent unexpected fallback addresses.
          .build()
    }
  }
}
