/*
 * Copyright (C) 2015 Square, Inc.
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
package okhttp3.internal.connection

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import okhttp3.Address
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Route
import okhttp3.TestUtil.awaitGarbageCollection
import okhttp3.internal.RecordingOkAuthenticator
import okhttp3.internal.concurrent.TaskFaker
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.connection.RealConnection.Companion.newTestConnection
import okhttp3.internal.http.RealInterceptorChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ConnectionPoolTest {
  /** The fake task runner prevents the cleanup runnable from being started.  */
  private val taskRunner = TaskFaker().taskRunner
  private val addressA = newAddress("a")
  private val routeA1 = newRoute(addressA)
  private val addressB = newAddress("b")
  private val routeB1 = newRoute(addressB)
  private val addressC = newAddress("c")
  private val routeC1 = newRoute(addressC)

  @Test fun connectionsEvictedWhenIdleLongEnough() {
    val pool = RealConnectionPool(
      taskRunner = taskRunner,
      maxIdleConnections = Int.MAX_VALUE,
      keepAliveDuration = 100L,
      timeUnit = TimeUnit.NANOSECONDS
    )
    val c1 = newConnection(pool, routeA1, 50L)

    // Running at time 50, the pool returns that nothing can be evicted until time 150.
    assertThat(pool.cleanup(50L)).isEqualTo(100L)
    assertThat(pool.connectionCount()).isEqualTo(1)
    assertThat(c1.socket().isClosed).isFalse

    // Running at time 60, the pool returns that nothing can be evicted until time 150.
    assertThat(pool.cleanup(60L)).isEqualTo(90L)
    assertThat(pool.connectionCount()).isEqualTo(1)
    assertThat(c1.socket().isClosed).isFalse

    // Running at time 149, the pool returns that nothing can be evicted until time 150.
    assertThat(pool.cleanup(149L)).isEqualTo(1L)
    assertThat(pool.connectionCount()).isEqualTo(1)
    assertThat(c1.socket().isClosed).isFalse

    // Running at time 150, the pool evicts.
    assertThat(pool.cleanup(150L)).isEqualTo(0)
    assertThat(pool.connectionCount()).isEqualTo(0)
    assertThat(c1.socket().isClosed).isTrue

    // Running again, the pool reports that no further runs are necessary.
    assertThat(pool.cleanup(150L)).isEqualTo(-1)
    assertThat(pool.connectionCount()).isEqualTo(0)
    assertThat(c1.socket().isClosed).isTrue
  }

  @Test fun inUseConnectionsNotEvicted() {
    val pool = RealConnectionPool(
      taskRunner = taskRunner,
      maxIdleConnections = Int.MAX_VALUE,
      keepAliveDuration = 100L,
      timeUnit = TimeUnit.NANOSECONDS
    )
    val poolApi = ConnectionPool(pool)
    val c1 = newConnection(pool, routeA1, 50L)
    val client = OkHttpClient.Builder()
      .connectionPool(poolApi)
      .build()
    val call = client.newCall(newRequest(addressA)) as RealCall
    call.enterNetworkInterceptorExchange(call.request(), true, newChain(call))
    synchronized(c1) { call.acquireConnectionNoEvents(c1) }

    // Running at time 50, the pool returns that nothing can be evicted until time 150.
    assertThat(pool.cleanup(50L)).isEqualTo(100L)
    assertThat(pool.connectionCount()).isEqualTo(1)
    assertThat(c1.socket().isClosed).isFalse

    // Running at time 60, the pool returns that nothing can be evicted until time 160.
    assertThat(pool.cleanup(60L)).isEqualTo(100L)
    assertThat(pool.connectionCount()).isEqualTo(1)
    assertThat(c1.socket().isClosed).isFalse

    // Running at time 160, the pool returns that nothing can be evicted until time 260.
    assertThat(pool.cleanup(160L)).isEqualTo(100L)
    assertThat(pool.connectionCount()).isEqualTo(1)
    assertThat(c1.socket().isClosed).isFalse
  }

  @Test fun cleanupPrioritizesEarliestEviction() {
    val pool = RealConnectionPool(
      taskRunner = taskRunner,
      maxIdleConnections = Int.MAX_VALUE,
      keepAliveDuration = 100L,
      timeUnit = TimeUnit.NANOSECONDS
    )
    val c1 = newConnection(pool, routeA1, 75L)
    val c2 = newConnection(pool, routeB1, 50L)

    // Running at time 75, the pool returns that nothing can be evicted until time 150.
    assertThat(pool.cleanup(75L)).isEqualTo(75L)
    assertThat(pool.connectionCount()).isEqualTo(2)

    // Running at time 149, the pool returns that nothing can be evicted until time 150.
    assertThat(pool.cleanup(149L)).isEqualTo(1L)
    assertThat(pool.connectionCount()).isEqualTo(2)

    // Running at time 150, the pool evicts c2.
    assertThat(pool.cleanup(150L)).isEqualTo(0L)
    assertThat(pool.connectionCount()).isEqualTo(1)
    assertThat(c1.socket().isClosed).isFalse
    assertThat(c2.socket().isClosed).isTrue

    // Running at time 150, the pool returns that nothing can be evicted until time 175.
    assertThat(pool.cleanup(150L)).isEqualTo(25L)
    assertThat(pool.connectionCount()).isEqualTo(1)

    // Running at time 175, the pool evicts c1.
    assertThat(pool.cleanup(175L)).isEqualTo(0L)
    assertThat(pool.connectionCount()).isEqualTo(0)
    assertThat(c1.socket().isClosed).isTrue
    assertThat(c2.socket().isClosed).isTrue
  }

  @Test fun oldestConnectionsEvictedIfIdleLimitExceeded() {
    val pool = RealConnectionPool(
      taskRunner = taskRunner,
      maxIdleConnections = 2,
      keepAliveDuration = 100L,
      timeUnit = TimeUnit.NANOSECONDS
    )
    val c1 = newConnection(pool, routeA1, 50L)
    val c2 = newConnection(pool, routeB1, 75L)

    // With 2 connections, there's no need to evict until the connections time out.
    assertThat(pool.cleanup(100L)).isEqualTo(50L)
    assertThat(pool.connectionCount()).isEqualTo(2)
    assertThat(c1.socket().isClosed).isFalse
    assertThat(c2.socket().isClosed).isFalse

    // Add a third connection
    val c3 = newConnection(pool, routeC1, 75L)

    // The third connection bounces the first.
    assertThat(pool.cleanup(100L)).isEqualTo(0L)
    assertThat(pool.connectionCount()).isEqualTo(2)
    assertThat(c1.socket().isClosed).isTrue
    assertThat(c2.socket().isClosed).isFalse
    assertThat(c3.socket().isClosed).isFalse
  }

  @Test fun leakedAllocation() {
    val pool = RealConnectionPool(
      taskRunner = taskRunner,
      maxIdleConnections = Int.MAX_VALUE,
      keepAliveDuration = 100L,
      timeUnit = TimeUnit.NANOSECONDS
    )
    val poolApi = ConnectionPool(pool)
    val c1 = newConnection(pool, routeA1, 0L)
    allocateAndLeakAllocation(poolApi, c1)
    awaitGarbageCollection()
    assertThat(pool.cleanup(100L)).isEqualTo(0L)
    assertThat(c1.calls).isEmpty()

    // Can't allocate once a leak has been detected.
    assertThat(c1.noNewExchanges).isTrue
  }

  @Test fun interruptStopsThread() {
    val realTaskRunner = TaskRunner.INSTANCE
    val pool = RealConnectionPool(
      realTaskRunner, 2, 100L, TimeUnit.NANOSECONDS
    )
    newConnection(pool, routeA1, Long.MAX_VALUE)
    assertThat(realTaskRunner.activeQueues()).isNotEmpty
    Thread.sleep(100)
    val threads = arrayOfNulls<Thread>(Thread.activeCount() * 2)
    Thread.enumerate(threads)
    for (t in threads) {
      if (t != null && t.name == "OkHttp TaskRunner") {
        t.interrupt()
      }
    }
    Thread.sleep(100)
    assertThat(realTaskRunner.activeQueues()).isEmpty()
  }

  /** Use a helper method so there's no hidden reference remaining on the stack.  */
  private fun allocateAndLeakAllocation(pool: ConnectionPool, connection: RealConnection) {
    val client = OkHttpClient.Builder()
      .connectionPool(pool)
      .build()
    val call = client.newCall(newRequest(connection.route().address)) as RealCall
    call.enterNetworkInterceptorExchange(call.request(), true, newChain(call))
    synchronized(connection) { call.acquireConnectionNoEvents(connection) }
  }

  private fun newConnection(
    pool: RealConnectionPool,
    route: Route,
    idleAtNanos: Long
  ): RealConnection {
    val result = newTestConnection(
      taskRunner = TaskRunner.INSTANCE,
      connectionPool = pool,
      route = route,
      socket = Socket(),
      idleAtNs = idleAtNanos
    )
    synchronized(result) { pool.put(result) }
    return result
  }

  private fun newAddress(name: String): Address {
    return Address(
      uriHost = name,
      uriPort = 1,
      dns = Dns.SYSTEM,
      socketFactory = SocketFactory.getDefault(),
      sslSocketFactory = null,
      hostnameVerifier = null,
      certificatePinner = null,
      proxyAuthenticator = RecordingOkAuthenticator("password", null),
      proxy = null,
      protocols = emptyList(),
      connectionSpecs = emptyList(),
      proxySelector = ProxySelector.getDefault()
    )
  }

  private fun newRoute(address: Address): Route {
    return Route(
      address = address,
      proxy = Proxy.NO_PROXY,
      socketAddress = InetSocketAddress.createUnresolved(address.url.host, address.url.port)
    )
  }

  private fun newChain(call: RealCall): RealInterceptorChain {
    return RealInterceptorChain(
      call = call,
      interceptors = listOf(),
      index = 0,
      exchange = null,
      request = call.request(),
      connectTimeoutMillis = 10_000,
      readTimeoutMillis = 10_000,
      writeTimeoutMillis = 10_000
    )
  }

  private fun newRequest(address: Address): Request {
    return Request.Builder()
      .url(address.url)
      .build()
  }
}
