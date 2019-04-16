/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3.internal.connection

import okhttp3.Address
import okhttp3.Route
import okhttp3.internal.Util
import okhttp3.internal.Util.closeQuietly
import okhttp3.internal.connection.Transmitter.TransmitterReference
import okhttp3.internal.notifyAll
import okhttp3.internal.platform.Platform
import okhttp3.internal.waitNanos
import java.io.IOException
import java.net.Proxy
import java.util.ArrayDeque
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class RealConnectionPool(
  /** The maximum number of idle connections for each address.  */
  private val maxIdleConnections: Int,
  keepAliveDuration: Long,
  timeUnit: TimeUnit
) {
  private val keepAliveDurationNs: Long = timeUnit.toNanos(keepAliveDuration)

  private val cleanupRunnable = object : Runnable {
    override fun run() {
      while (true) {
        val waitNanos = cleanup(System.nanoTime())
        if (waitNanos == -1L) return
        try {
          this@RealConnectionPool.waitNanos(waitNanos)
        } catch (_: InterruptedException) {
        }
      }
    }
  }

  private val connections = ArrayDeque<RealConnection>()
  val routeDatabase = RouteDatabase()
  var cleanupRunning: Boolean = false

  init {
    // Put a floor on the keep alive duration, otherwise cleanup will spin loop.
    require(keepAliveDuration > 0) { "keepAliveDuration <= 0: $keepAliveDuration" }
  }

  @Synchronized fun idleConnectionCount(): Int {
    return connections.count { it.transmitters.isEmpty() }
  }

  @Synchronized fun connectionCount(): Int {
    return connections.size
  }

  /**
   * Attempts to acquire a recycled connection to `address` for `transmitter`. Returns true if a
   * connection was acquired.
   *
   * If `routes` is non-null these are the resolved routes (ie. IP addresses) for the connection.
   * This is used to coalesce related domains to the same HTTP/2 connection, such as `square.com`
   * and `square.ca`.
   */
  fun transmitterAcquirePooledConnection(
    address: Address,
    transmitter: Transmitter,
    routes: List<Route>?,
    requireMultiplexed: Boolean
  ): Boolean {
    assert(Thread.holdsLock(this))
    for (connection in connections) {
      if (requireMultiplexed && !connection.isMultiplexed) continue
      if (!connection.isEligible(address, routes)) continue
      transmitter.acquireConnectionNoEvents(connection)
      return true
    }
    return false
  }

  fun put(connection: RealConnection) {
    assert(Thread.holdsLock(this))
    if (!cleanupRunning) {
      cleanupRunning = true
      executor.execute(cleanupRunnable)
    }
    connections.add(connection)
  }

  /**
   * Notify this pool that `connection` has become idle. Returns true if the connection has
   * been removed from the pool and should be closed.
   */
  fun connectionBecameIdle(connection: RealConnection): Boolean {
    assert(Thread.holdsLock(this))
    if (connection.noNewExchanges || maxIdleConnections == 0) {
      connections.remove(connection)
      return true
    } else {
      // Awake the cleanup thread: we may have exceeded the idle connection limit.
      this.notifyAll()
      return false
    }
  }

  fun evictAll() {
    val evictedConnections = mutableListOf<RealConnection>()
    synchronized(this) {
      val i = connections.iterator()
      while (i.hasNext()) {
        val connection = i.next()
        if (connection.transmitters.isEmpty()) {
          connection.noNewExchanges = true
          evictedConnections.add(connection)
          i.remove()
        }
      }
    }

    for (connection in evictedConnections) {
      closeQuietly(connection.socket())
    }
  }

  /**
   * Performs maintenance on this pool, evicting the connection that has been idle the longest if
   * either it has exceeded the keep alive limit or the idle connections limit.
   *
   * Returns the duration in nanos to sleep until the next scheduled call to this method. Returns
   * -1 if no further cleanups are required.
   */
  fun cleanup(now: Long): Long {
    var inUseConnectionCount = 0
    var idleConnectionCount = 0
    var longestIdleConnection: RealConnection? = null
    var longestIdleDurationNs = Long.MIN_VALUE

    // Find either a connection to evict, or the time that the next eviction is due.
    synchronized(this) {
      for (connection in connections) {
        // If the connection is in use, keep searching.
        if (pruneAndGetAllocationCount(connection, now) > 0) {
          inUseConnectionCount++
          continue
        }

        idleConnectionCount++

        // If the connection is ready to be evicted, we're done.
        val idleDurationNs = now - connection.idleAtNanos
        if (idleDurationNs > longestIdleDurationNs) {
          longestIdleDurationNs = idleDurationNs
          longestIdleConnection = connection
        }
      }

      when {
        longestIdleDurationNs >= this.keepAliveDurationNs
            || idleConnectionCount > this.maxIdleConnections -> {
          // We've found a connection to evict. Remove it from the list, then close it below
          // (outside of the synchronized block).
          connections.remove(longestIdleConnection)
        }
        idleConnectionCount > 0 -> {
          // A connection will be ready to evict soon.
          return keepAliveDurationNs - longestIdleDurationNs
        }
        inUseConnectionCount > 0 -> {
          // All connections are in use. It'll be at least the keep alive duration 'til we run
          // again.
          return keepAliveDurationNs
        }
        else -> {
          // No connections, idle or in use.
          cleanupRunning = false
          return -1
        }
      }
    }

    closeQuietly(longestIdleConnection!!.socket())

    // Cleanup again immediately.
    return 0
  }

  /**
   * Prunes any leaked transmitters and then returns the number of remaining live transmitters on
   * `connection`. Transmitters are leaked if the connection is tracking them but the application
   * code has abandoned them. Leak detection is imprecise and relies on garbage collection.
   */
  private fun pruneAndGetAllocationCount(connection: RealConnection, now: Long): Int {
    val references = connection.transmitters
    var i = 0
    while (i < references.size) {
      val reference = references[i]

      if (reference.get() != null) {
        i++
        continue
      }

      // We've discovered a leaked transmitter. This is an application bug.
      val transmitterRef = reference as TransmitterReference
      val message = "A connection to ${connection.route().address().url()} was leaked. " +
          "Did you forget to close a response body?"
      Platform.get().logCloseableLeak(message, transmitterRef.callStackTrace)

      references.removeAt(i)
      connection.noNewExchanges = true

      // If this was the last allocation, the connection is eligible for immediate eviction.
      if (references.isEmpty()) {
        connection.idleAtNanos = now - keepAliveDurationNs
        return 0
      }
    }

    return references.size
  }

  /** Track a bad route in the route database. Other routes will be attempted first.  */
  fun connectFailed(failedRoute: Route, failure: IOException) {
    // Tell the proxy selector when we fail to connect on a fresh connection.
    if (failedRoute.proxy().type() != Proxy.Type.DIRECT) {
      val address = failedRoute.address()
      address.proxySelector().connectFailed(
          address.url().uri(), failedRoute.proxy().address(), failure)
    }

    routeDatabase.failed(failedRoute)
  }

  companion object {
    /**
     * Background threads are used to cleanup expired connections. There will be at most a single
     * thread running per connection pool. The thread pool executor permits the pool itself to be
     * garbage collected.
     */
    private val executor = ThreadPoolExecutor(
        0, // corePoolSize.
        Int.MAX_VALUE, // maximumPoolSize.
        60L, TimeUnit.SECONDS, // keepAliveTime.
        SynchronousQueue(),
        Util.threadFactory("OkHttp ConnectionPool", true)
    )
  }
}
