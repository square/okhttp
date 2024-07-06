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

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import okhttp3.Address
import okhttp3.ConnectionPool
import okhttp3.FakeRoutePlanner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TestUtil.awaitGarbageCollection
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.connection.Locks.withLock
import okhttp3.internal.http2.Http2
import okhttp3.internal.http2.Http2Connection
import okhttp3.internal.http2.Http2ConnectionTest
import okhttp3.internal.http2.MockHttp2Peer
import okhttp3.internal.http2.Settings
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class ConnectionPoolTest {
  private val routePlanner = FakeRoutePlanner()
  private val factory = routePlanner.factory
  private val taskFaker = routePlanner.taskFaker
  private val peer = MockHttp2Peer()

  /** The fake task runner prevents the cleanup runnable from being started.  */
  private val addressA = factory.newAddress("a")
  private val routeA1 = factory.newRoute(addressA)
  private val addressB = factory.newAddress("b")
  private val routeB1 = factory.newRoute(addressB)
  private val addressC = factory.newAddress("c")
  private val routeC1 = factory.newRoute(addressC)

  @AfterEach fun tearDown() {
    factory.close()
    peer.close()
  }

  @Test fun connectionsEvictedWhenIdleLongEnough() {
    val pool = factory.newConnectionPool()
    val c1 = factory.newConnection(pool, routeA1, 50L)

    // Running at time 50, the pool returns that nothing can be evicted until time 150.
    assertThat(pool.closeConnections(50L)).isEqualTo(100L)
    assertThat(pool.connectionCount()).isEqualTo(1)
    assertThat(c1.socket().isClosed).isFalse()

    // Running at time 60, the pool returns that nothing can be evicted until time 150.
    assertThat(pool.closeConnections(60L)).isEqualTo(90L)
    assertThat(pool.connectionCount()).isEqualTo(1)
    assertThat(c1.socket().isClosed).isFalse()

    // Running at time 149, the pool returns that nothing can be evicted until time 150.
    assertThat(pool.closeConnections(149L)).isEqualTo(1L)
    assertThat(pool.connectionCount()).isEqualTo(1)
    assertThat(c1.socket().isClosed).isFalse()

    // Running at time 150, the pool evicts.
    assertThat(pool.closeConnections(150L)).isEqualTo(0)
    assertThat(pool.connectionCount()).isEqualTo(0)
    assertThat(c1.socket().isClosed).isTrue()

    // Running again, the pool reports that no further runs are necessary.
    assertThat(pool.closeConnections(150L)).isEqualTo(-1)
    assertThat(pool.connectionCount()).isEqualTo(0)
    assertThat(c1.socket().isClosed).isTrue()
  }

  @Test fun inUseConnectionsNotEvicted() {
    val pool = factory.newConnectionPool()
    val poolApi = ConnectionPool(pool)
    val c1 = factory.newConnection(pool, routeA1, 50L)
    val client =
      OkHttpClient.Builder()
        .connectionPool(poolApi)
        .build()
    val call = client.newCall(Request(addressA.url)) as RealCall
    call.enterNetworkInterceptorExchange(call.request(), true, factory.newChain(call))
    c1.withLock { call.acquireConnectionNoEvents(c1) }

    // Running at time 50, the pool returns that nothing can be evicted until time 150.
    assertThat(pool.closeConnections(50L)).isEqualTo(100L)
    assertThat(pool.connectionCount()).isEqualTo(1)
    assertThat(c1.socket().isClosed).isFalse()

    // Running at time 60, the pool returns that nothing can be evicted until time 160.
    assertThat(pool.closeConnections(60L)).isEqualTo(100L)
    assertThat(pool.connectionCount()).isEqualTo(1)
    assertThat(c1.socket().isClosed).isFalse()

    // Running at time 160, the pool returns that nothing can be evicted until time 260.
    assertThat(pool.closeConnections(160L)).isEqualTo(100L)
    assertThat(pool.connectionCount()).isEqualTo(1)
    assertThat(c1.socket().isClosed).isFalse()
  }

  @Test fun cleanupPrioritizesEarliestEviction() {
    val pool = factory.newConnectionPool()
    val c1 = factory.newConnection(pool, routeA1, 75L)
    val c2 = factory.newConnection(pool, routeB1, 50L)

    // Running at time 75, the pool returns that nothing can be evicted until time 150.
    assertThat(pool.closeConnections(75L)).isEqualTo(75L)
    assertThat(pool.connectionCount()).isEqualTo(2)

    // Running at time 149, the pool returns that nothing can be evicted until time 150.
    assertThat(pool.closeConnections(149L)).isEqualTo(1L)
    assertThat(pool.connectionCount()).isEqualTo(2)

    // Running at time 150, the pool evicts c2.
    assertThat(pool.closeConnections(150L)).isEqualTo(0L)
    assertThat(pool.connectionCount()).isEqualTo(1)
    assertThat(c1.socket().isClosed).isFalse()
    assertThat(c2.socket().isClosed).isTrue()

    // Running at time 150, the pool returns that nothing can be evicted until time 175.
    assertThat(pool.closeConnections(150L)).isEqualTo(25L)
    assertThat(pool.connectionCount()).isEqualTo(1)

    // Running at time 175, the pool evicts c1.
    assertThat(pool.closeConnections(175L)).isEqualTo(0L)
    assertThat(pool.connectionCount()).isEqualTo(0)
    assertThat(c1.socket().isClosed).isTrue()
    assertThat(c2.socket().isClosed).isTrue()
  }

  @Test fun oldestConnectionsEvictedIfIdleLimitExceeded() {
    val pool =
      factory.newConnectionPool(
        maxIdleConnections = 2,
      )
    val c1 = factory.newConnection(pool, routeA1, 50L)
    val c2 = factory.newConnection(pool, routeB1, 75L)

    // With 2 connections, there's no need to evict until the connections time out.
    assertThat(pool.closeConnections(100L)).isEqualTo(50L)
    assertThat(pool.connectionCount()).isEqualTo(2)
    assertThat(c1.socket().isClosed).isFalse()
    assertThat(c2.socket().isClosed).isFalse()

    // Add a third connection
    val c3 = factory.newConnection(pool, routeC1, 75L)

    // The third connection bounces the first.
    assertThat(pool.closeConnections(100L)).isEqualTo(0L)
    assertThat(pool.connectionCount()).isEqualTo(2)
    assertThat(c1.socket().isClosed).isTrue()
    assertThat(c2.socket().isClosed).isFalse()
    assertThat(c3.socket().isClosed).isFalse()
  }

  @Test fun leakedAllocation() {
    val pool = factory.newConnectionPool()
    val poolApi = ConnectionPool(pool)
    val c1 = factory.newConnection(pool, routeA1, 0L)
    allocateAndLeakAllocation(poolApi, c1)
    awaitGarbageCollection()
    assertThat(pool.closeConnections(100L)).isEqualTo(0L)
    assertThat(c1.calls).isEmpty()

    // Can't allocate once a leak has been detected.
    assertThat(c1.noNewExchanges).isTrue()
  }

  @Test fun interruptStopsThread() {
    val realTaskRunner = TaskRunner.INSTANCE
    val pool =
      factory.newConnectionPool(
        taskRunner = TaskRunner.INSTANCE,
        maxIdleConnections = 2,
      )
    factory.newConnection(pool, routeA1)
    assertThat(realTaskRunner.activeQueues()).isNotEmpty()
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

  @Test fun connectionPreWarmingHttp1() {
    taskFaker.advanceUntil(System.nanoTime())
    val expireTime = taskFaker.nanoTime + 1_000_000_000_000

    routePlanner.autoGeneratePlans = true
    routePlanner.defaultConnectionIdleAtNanos = expireTime
    val address = routePlanner.address
    val pool = routePlanner.pool

    // Connections are created as soon as a policy is set
    setPolicy(pool, address, ConnectionPool.AddressPolicy(2))
    assertThat(pool.connectionCount()).isEqualTo(2)

    // Connections are replaced if they idle out or are evicted from the pool
    evictAllConnections(pool)
    assertThat(pool.connectionCount()).isEqualTo(2)
    forceConnectionsToExpire(pool, expireTime)
    assertThat(pool.connectionCount()).isEqualTo(2)

    // Excess connections aren't removed until they idle out, even if no longer needed
    setPolicy(pool, address, ConnectionPool.AddressPolicy(1))
    assertThat(pool.connectionCount()).isEqualTo(2)
    forceConnectionsToExpire(pool, expireTime)
    assertThat(pool.connectionCount()).isEqualTo(1)
  }

  @Disabled("https://github.com/square/okhttp/issues/8451")
  @Test
  fun connectionPreWarmingHttp2() {
    taskFaker.advanceUntil(System.nanoTime())
    val expireSooner = taskFaker.nanoTime + 1_000_000_000_000
    val expireLater = taskFaker.nanoTime + 2_000_000_000_000

    routePlanner.autoGeneratePlans = true
    val address = routePlanner.address
    val pool = routePlanner.pool

    // Add a connection to the pool that won't expire for a while
    routePlanner.defaultConnectionIdleAtNanos = expireLater
    setPolicy(pool, address, ConnectionPool.AddressPolicy(1))
    assertThat(pool.connectionCount()).isEqualTo(1)

    // All other connections created will expire sooner
    routePlanner.defaultConnectionIdleAtNanos = expireSooner

    // Turn it into an http/2 connection that supports 5 concurrent streams
    // which can satisfy a larger policy
    val connection = routePlanner.plans.first().connection
    val http2Connection = connectHttp2(peer, connection, 5)
    setPolicy(pool, address, ConnectionPool.AddressPolicy(5))
    assertThat(pool.connectionCount()).isEqualTo(1)

    // Decrease the connection's max so that another connection is needed
    updateMaxConcurrentStreams(http2Connection, 4)
    assertThat(pool.connectionCount()).isEqualTo(2)

    // Increase the connection's max so that the new connection is no longer needed
    updateMaxConcurrentStreams(http2Connection, 5)
    forceConnectionsToExpire(pool, expireSooner)
    assertThat(pool.connectionCount()).isEqualTo(1)
  }

  private fun setPolicy(
    pool: RealConnectionPool,
    address: Address,
    policy: ConnectionPool.AddressPolicy,
  ) {
    pool.setPolicy(address, policy)
    taskFaker.runTasks()
  }

  private fun evictAllConnections(pool: RealConnectionPool) {
    pool.evictAll()
    assertThat(pool.connectionCount()).isEqualTo(0)
    taskFaker.runTasks()
  }

  private fun forceConnectionsToExpire(
    pool: RealConnectionPool,
    expireTime: Long,
  ) {
    val idleTimeNanos = expireTime + pool.keepAliveDurationNs
    repeat(pool.connectionCount()) { pool.closeConnections(idleTimeNanos) }
    taskFaker.runTasks()
  }

  private fun connectHttp2(
    peer: MockHttp2Peer,
    realConnection: RealConnection,
    maxConcurrentStreams: Int,
  ): Http2Connection {
    // Write the mocking script.
    val settings1 = Settings()
    settings1[Settings.MAX_CONCURRENT_STREAMS] = maxConcurrentStreams
    peer.sendFrame().settings(settings1)
    peer.acceptFrame() // ACK
    peer.sendFrame().ping(false, 2, 0)
    peer.acceptFrame() // PING
    peer.play()

    // Play it back.
    val connection =
      Http2Connection.Builder(true, TaskRunner.INSTANCE)
        .socket(peer.openSocket())
        .pushObserver(Http2ConnectionTest.IGNORE)
        .listener(realConnection)
        .build()
    connection.start(sendConnectionPreface = false)

    // verify the peer received the ACK
    val ackFrame = peer.takeFrame()
    assertThat(ackFrame.type).isEqualTo(Http2.TYPE_SETTINGS)
    assertThat(ackFrame.streamId).isEqualTo(0)
    assertThat(ackFrame.ack).isTrue()

    taskFaker.runTasks()

    return connection
  }

  private fun updateMaxConcurrentStreams(
    connection: Http2Connection,
    amount: Int,
  ) {
    val settings = Settings()
    settings[Settings.MAX_CONCURRENT_STREAMS] = amount
    connection.readerRunnable.applyAndAckSettings(true, settings)
    assertThat(connection.peerSettings[Settings.MAX_CONCURRENT_STREAMS]).isEqualTo(amount)
    taskFaker.runTasks()
  }

  /** Use a helper method so there's no hidden reference remaining on the stack.  */
  private fun allocateAndLeakAllocation(
    pool: ConnectionPool,
    connection: RealConnection,
  ) {
    val client =
      OkHttpClient.Builder()
        .connectionPool(pool)
        .build()
    val call = client.newCall(Request(connection.route().address.url)) as RealCall
    call.enterNetworkInterceptorExchange(call.request(), true, factory.newChain(call))
    connection.withLock { call.acquireConnectionNoEvents(connection) }
  }
}
