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

import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import okhttp3.Address
import okhttp3.ConnectionPool
import okhttp3.Route
import okhttp3.internal.assertThreadHoldsLock
import okhttp3.internal.closeQuietly
import okhttp3.internal.concurrent.Task
import okhttp3.internal.concurrent.TaskQueue
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.connection.RealCall.CallReference
import okhttp3.internal.okHttpName
import okhttp3.internal.platform.Platform

class RealConnectionPool(
  taskRunner: TaskRunner,
  /** The maximum number of idle connections for each address. */
  private val maxIdleConnections: Int,
  keepAliveDuration: Long,
  timeUnit: TimeUnit
) {
  private val keepAliveDurationNs: Long = timeUnit.toNanos(keepAliveDuration)

  private val cleanupQueue: TaskQueue = taskRunner.newQueue()
  private val cleanupTask = object : Task("$okHttpName ConnectionPool") {
    override fun runOnce() = cleanup(System.nanoTime())
  }

  private val connections = ArrayDeque<RealConnection>()

  init {
    // Put a floor on the keep alive duration, otherwise cleanup will spin loop.
    require(keepAliveDuration > 0L) { "keepAliveDuration <= 0: $keepAliveDuration" }
  }

  @Synchronized fun idleConnectionCount(): Int {
    return connections.count { it.calls.isEmpty() }
  }

  @Synchronized fun connectionCount(): Int {
    return connections.size
  }

  /**
   * Attempts to acquire a recycled connection to [address] for [call]. Returns true if a connection
   * was acquired.
   *
   * If [routes] is non-null these are the resolved routes (ie. IP addresses) for the connection.
   * This is used to coalesce related domains to the same HTTP/2 connection, such as `square.com`
   * and `square.ca`.
   */
  fun callAcquirePooledConnection(
    address: Address,
    call: RealCall,
    routes: List<Route>?,
    requireMultiplexed: Boolean
  ): Boolean {
    this.assertThreadHoldsLock()

    for (connection in connections) {
      if (requireMultiplexed && !connection.isMultiplexed) continue
      if (!connection.isEligible(address, routes)) continue
      call.acquireConnectionNoEvents(connection)
      return true
    }
    return false
  }

  fun put(connection: RealConnection) {
    this.assertThreadHoldsLock()

    connections.add(connection)
    cleanupQueue.schedule(cleanupTask)
  }

  /**
   * Notify this pool that [connection] has become idle. Returns true if the connection has
   * been removed from the pool and should be closed.
   */
  fun connectionBecameIdle(connection: RealConnection): Boolean {
    this.assertThreadHoldsLock()

    return if (connection.noNewExchanges || maxIdleConnections == 0) {
      connections.remove(connection)
      if (connections.isEmpty()) cleanupQueue.cancelAll()
      true
    } else {
      cleanupQueue.schedule(cleanupTask)
      false
    }
  }

  fun evictAll() {
    val evictedConnections = mutableListOf<RealConnection>()
    synchronized(this) {
      val i = connections.iterator()
      while (i.hasNext()) {
        val connection = i.next()
        if (connection.calls.isEmpty()) {
          connection.noNewExchanges = true
          evictedConnections.add(connection)
          i.remove()
        }
      }
      if (connections.isEmpty()) cleanupQueue.cancelAll()
    }

    for (connection in evictedConnections) {
      connection.socket().closeQuietly()
    }
  }

  /**
   * Performs maintenance on this pool, evicting the connection that has been idle the longest if
   * either it has exceeded the keep alive limit or the idle connections limit.
   *
   * Returns the duration in nanoseconds to sleep until the next scheduled call to this method.
   * Returns -1 if no further cleanups are required.
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
        val idleDurationNs = now - connection.idleAtNs
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
          if (connections.isEmpty()) cleanupQueue.cancelAll()
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
          return -1
        }
      }
    }

    longestIdleConnection!!.socket().closeQuietly()

    // Cleanup again immediately.
    return 0L
  }

  /**
   * Prunes any leaked calls and then returns the number of remaining live calls on [connection].
   * Calls are leaked if the connection is tracking them but the application code has abandoned
   * them. Leak detection is imprecise and relies on garbage collection.
   */
  private fun pruneAndGetAllocationCount(connection: RealConnection, now: Long): Int {
    val references = connection.calls
    var i = 0
    while (i < references.size) {
      val reference = references[i]

      if (reference.get() != null) {
        i++
        continue
      }

      // We've discovered a leaked call. This is an application bug.
      val callReference = reference as CallReference
      val message = "A connection to ${connection.route().address.url} was leaked. " +
          "Did you forget to close a response body?"
      Platform.get().logCloseableLeak(message, callReference.callStackTrace)

      references.removeAt(i)
      connection.noNewExchanges = true

      // If this was the last allocation, the connection is eligible for immediate eviction.
      if (references.isEmpty()) {
        connection.idleAtNs = now - keepAliveDurationNs
        return 0
      }
    }

    return references.size
  }

  companion object {
    fun get(connectionPool: ConnectionPool): RealConnectionPool = connectionPool.delegate
  }
}
