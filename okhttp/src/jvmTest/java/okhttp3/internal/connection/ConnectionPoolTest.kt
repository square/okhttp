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

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TestUtil.awaitGarbageCollection
import okhttp3.TestValueFactory
import okhttp3.internal.concurrent.TaskRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class ConnectionPoolTest {
  private val factory = TestValueFactory()

  /** The fake task runner prevents the cleanup runnable from being started.  */
  private val addressA = factory.newAddress("a")
  private val routeA1 = factory.newRoute(addressA)
  private val addressB = factory.newAddress("b")
  private val routeB1 = factory.newRoute(addressB)
  private val addressC = factory.newAddress("c")
  private val routeC1 = factory.newRoute(addressC)

  @AfterEach fun tearDown() {
    factory.close()
  }

  @Test fun connectionsEvictedWhenIdleLongEnough() {
    val pool = factory.newConnectionPool()
    val c1 = factory.newConnection(pool, routeA1, 50L)

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
    val pool = factory.newConnectionPool()
    val poolApi = ConnectionPool(pool)
    val c1 = factory.newConnection(pool, routeA1, 50L)
    val client = OkHttpClient.Builder()
      .connectionPool(poolApi)
      .build()
    val call = client.newCall(Request(addressA.url)) as RealCall
    call.enterNetworkInterceptorExchange(call.request(), true, factory.newChain(call))
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
    val pool = factory.newConnectionPool()
    val c1 = factory.newConnection(pool, routeA1, 75L)
    val c2 = factory.newConnection(pool, routeB1, 50L)

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
    val pool = factory.newConnectionPool(
      maxIdleConnections = 2
    )
    val c1 = factory.newConnection(pool, routeA1, 50L)
    val c2 = factory.newConnection(pool, routeB1, 75L)

    // With 2 connections, there's no need to evict until the connections time out.
    assertThat(pool.cleanup(100L)).isEqualTo(50L)
    assertThat(pool.connectionCount()).isEqualTo(2)
    assertThat(c1.socket().isClosed).isFalse
    assertThat(c2.socket().isClosed).isFalse

    // Add a third connection
    val c3 = factory.newConnection(pool, routeC1, 75L)

    // The third connection bounces the first.
    assertThat(pool.cleanup(100L)).isEqualTo(0L)
    assertThat(pool.connectionCount()).isEqualTo(2)
    assertThat(c1.socket().isClosed).isTrue
    assertThat(c2.socket().isClosed).isFalse
    assertThat(c3.socket().isClosed).isFalse
  }

  @Test fun leakedAllocation() {
    val pool = factory.newConnectionPool()
    val poolApi = ConnectionPool(pool)
    val c1 = factory.newConnection(pool, routeA1, 0L)
    allocateAndLeakAllocation(poolApi, c1)
    awaitGarbageCollection()
    assertThat(pool.cleanup(100L)).isEqualTo(0L)
    assertThat(c1.calls).isEmpty()

    // Can't allocate once a leak has been detected.
    assertThat(c1.noNewExchanges).isTrue
  }

  @Test fun interruptStopsThread() {
    val realTaskRunner = TaskRunner.INSTANCE
    val pool = factory.newConnectionPool(
      taskRunner = TaskRunner.INSTANCE,
      maxIdleConnections = 2
    )
    factory.newConnection(pool, routeA1)
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
    val call = client.newCall(Request(connection.route().address.url)) as RealCall
    call.enterNetworkInterceptorExchange(call.request(), true, factory.newChain(call))
    synchronized(connection) { call.acquireConnectionNoEvents(connection) }
  }
}
