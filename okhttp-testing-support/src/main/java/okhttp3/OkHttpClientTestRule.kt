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

import org.assertj.core.api.Assertions.assertThat
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedDeque

/** Apply this rule to tests that need an OkHttpClient instance. */
class OkHttpClientTestRule : TestRule {
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
    return checkNotNull(prototype) { "don't create clients in test initialization!" }.newBuilder()
  }

  fun ensureAllConnectionsReleased() {
    prototype?.let {
      val connectionPool = it.connectionPool
      connectionPool.evictAll()
      assertThat(connectionPool.connectionCount()).isEqualTo(0)
    }
  }

  override fun apply(base: Statement, description: Description): Statement {
    return object : Statement() {
      override fun evaluate() {
        acquireClient()
        try {
          base.evaluate()
        } finally {
          ensureAllConnectionsReleased()
          releaseClient()
        }
      }

      private fun acquireClient() {
        prototype = prototypes.poll() ?: OkHttpClient.Builder()
            .dns(SINGLE_INET_ADDRESS_DNS) // Prevent unexpected fallback addresses.
            .build()
      }

      private fun releaseClient() {
        prototype?.let {
          prototypes.push(it)
          prototype = null
        }
      }
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
  }
}
