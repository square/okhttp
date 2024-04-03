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

import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import okhttp3.Address
import okhttp3.ConnectionListener
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
  private val taskRunner: TaskRunner,
  /**
   * The maximum number of idle connections across all addresses.
   * Connections needed to satisfy a [ConnectionPool.AddressPolicy] are not considered idle.
   */
  private val maxIdleConnections: Int,
  keepAliveDuration: Long,
  timeUnit: TimeUnit,
  internal val connectionListener: ConnectionListener,
  private val exchangeFinderFactory: (RealConnectionPool, Address, ConnectionUser) -> ExchangeFinder,
) {
  internal val keepAliveDurationNs: Long = timeUnit.toNanos(keepAliveDuration)

  // guarded by [this]
  private var policies: Map<Address, MinimumConnectionState> = mapOf()
  private val user = PoolConnectionUser

  private val cleanupQueue: TaskQueue = taskRunner.newQueue()
  private val cleanupTask =
    object : Task("$okHttpName ConnectionPool connection closer") {
      override fun runOnce(): Long = cleanup(System.nanoTime())
    }

  private fun MinimumConnectionState.schedule() {
    val state = this
    queue.schedule(
      object : Task("$okHttpName ConnectionPool connection opener") {
        override fun runOnce(): Long = ensureMinimumConnections(state)
      },
    )
  }

  /**
   * Holding the lock of the connection being added or removed when mutating this, and check its
   * [RealConnection.noNewExchanges] property. This defends against races where a connection is
   * simultaneously adopted and removed.
   */
  private val connections = ConcurrentLinkedQueue<RealConnection>()

  init {
    // Put a floor on the keep alive duration, otherwise cleanup will spin loop.
    require(keepAliveDuration > 0L) { "keepAliveDuration <= 0: $keepAliveDuration" }
  }

  fun idleConnectionCount(): Int {
    return connections.count {
      synchronized(it) { it.calls.isEmpty() }
    }
  }

  fun connectionCount(): Int {
    return connections.size
  }

  /**
   * Attempts to acquire a recycled connection to [address] for [connectionUser]. Returns the connection if it
   * was acquired, or null if no connection was acquired. The acquired connection will also be
   * given to [connectionUser] who may (for example) assign it to a [RealCall.connection].
   *
   * This confirms the returned connection is healthy before returning it. If this encounters any
   * unhealthy connections in its search, this will clean them up.
   *
   * If [routes] is non-null these are the resolved routes (ie. IP addresses) for the connection.
   * This is used to coalesce related domains to the same HTTP/2 connection, such as `square.com`
   * and `square.ca`.
   */
  fun callAcquirePooledConnection(
    doExtensiveHealthChecks: Boolean,
    address: Address,
    connectionUser: ConnectionUser,
    routes: List<Route>?,
    requireMultiplexed: Boolean,
  ): RealConnection? {
    for (connection in connections) {
      // In the first synchronized block, acquire the connection if it can satisfy this call.
      val acquired =
        synchronized(connection) {
          when {
            requireMultiplexed && !connection.isMultiplexed -> false
            !connection.isEligible(address, routes) -> false
            else -> {
              connectionUser.acquireConnectionNoEvents(connection)
              true
            }
          }
        }
      if (!acquired) continue

      // Confirm the connection is healthy and return it.
      if (connection.isHealthy(doExtensiveHealthChecks)) return connection

      // In the second synchronized block, release the unhealthy acquired connection. We're also on
      // the hook to close this connection if it's no longer in use.
      val noNewExchangesEvent: Boolean
      val toClose: Socket? =
        synchronized(connection) {
          noNewExchangesEvent = !connection.noNewExchanges
          connection.noNewExchanges = true
          connectionUser.releaseConnectionNoEvents()
        }
      if (toClose != null) {
        toClose.closeQuietly()
        connectionListener.connectionClosed(connection)
      } else if (noNewExchangesEvent) {
        connectionListener.noNewExchanges(connection)
      }
    }
    return null
  }

  fun put(connection: RealConnection) {
    connection.assertThreadHoldsLock()

    connections.add(connection)
//    connection.queueEvent { connectionListener.connectEnd(connection) }
    scheduleConnectionCloser()
  }

  /**
   * Notify this pool that [connection] has become idle. Returns true if the connection has been
   * removed from the pool and should be closed.
   */
  fun connectionBecameIdle(connection: RealConnection): Boolean {
    connection.assertThreadHoldsLock()

    return if (connection.noNewExchanges || maxIdleConnections == 0) {
      connection.noNewExchanges = true
      connections.remove(connection)
      if (connections.isEmpty()) cleanupQueue.cancelAll()
      scheduleConnectionOpener()
      true
    } else {
      scheduleConnectionCloser()
      false
    }
  }

  fun evictAll() {
    val i = connections.iterator()
    while (i.hasNext()) {
      val connection = i.next()
      val socketToClose =
        synchronized(connection) {
          if (connection.calls.isEmpty()) {
            i.remove()
            connection.noNewExchanges = true
            return@synchronized connection.socket()
          } else {
            return@synchronized null
          }
        }
      if (socketToClose != null) {
        socketToClose.closeQuietly()
        connectionListener.connectionClosed(connection)
      }
    }

    if (connections.isEmpty()) cleanupQueue.cancelAll()
    scheduleConnectionOpener()
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
    var policyAffected: MinimumConnectionState? = null
    policies
      .forEach { it.value.unsatisfiedCountCleanupTask = it.value.policy.minimumConcurrentCalls }

    // Find either a connection to evict, or the time that the next eviction is due.
    for (connection in connections) {
      synchronized(connection) {
        val satisfiablePolicy =
          policies.entries.firstOrNull {
            it.value.unsatisfiedCountCleanupTask > 0 && connection.isEligible(it.key, null)
          }?.value
        val idleDurationNs = now - connection.idleAtNs

        if (pruneAndGetAllocationCount(connection, now) > 0) {
          // If the connection is in use, keep searching.
          inUseConnectionCount++
        } else if (satisfiablePolicy != null && idleDurationNs < this.keepAliveDurationNs) {
          // If the connection hasn't expired and helps satisfy a policy, keep searching.
          satisfiablePolicy.unsatisfiedCountCleanupTask -= connection.allocationLimit
          inUseConnectionCount++
        } else {
          idleConnectionCount++

          // If the connection is ready to be evicted, we're done.
          if (idleDurationNs > longestIdleDurationNs) {
            longestIdleDurationNs = idleDurationNs
            longestIdleConnection = connection
            policyAffected = satisfiablePolicy
          } else {
            Unit
          }
        }
      }
    }

    when {
      longestIdleDurationNs >= this.keepAliveDurationNs ||
        idleConnectionCount > this.maxIdleConnections -> {
        // We've chosen a connection to evict. Confirm it's still okay to be evicted, then close it.
        val connection = longestIdleConnection!!
        synchronized(connection) {
          if (connection.calls.isNotEmpty()) return 0L // No longer idle.
          if (connection.idleAtNs + longestIdleDurationNs != now) return 0L // No longer oldest.
          connection.noNewExchanges = true
          connections.remove(longestIdleConnection)
        }
        policyAffected?.schedule()
        connection.socket().closeQuietly()
        connectionListener.connectionClosed(connection)
        if (connections.isEmpty()) cleanupQueue.cancelAll()

        // Clean up again immediately.
        return 0L
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

  /**
   * Prunes any leaked calls and then returns the number of remaining live calls on [connection].
   * Calls are leaked if the connection is tracking them but the application code has abandoned
   * them. Leak detection is imprecise and relies on garbage collection.
   */
  private fun pruneAndGetAllocationCount(
    connection: RealConnection,
    now: Long,
  ): Int {
    connection.assertThreadHoldsLock()

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
      val message =
        "A connection to ${connection.route().address.url} was leaked. " +
          "Did you forget to close a response body?"
      Platform.get().logCloseableLeak(message, callReference.callStackTrace)

      references.removeAt(i)

      // If this was the last allocation, the connection is eligible for immediate eviction.
      if (references.isEmpty()) {
        connection.idleAtNs = now - keepAliveDurationNs
        return 0
      }
    }

    return references.size
  }

  /**
   * Adds or replaces the policy for [address].
   * This will trigger a background task to start creating connections as needed.
   */
  fun setPolicy(
    address: Address,
    policy: ConnectionPool.AddressPolicy,
  ) {
    val state = MinimumConnectionState(address, taskRunner.newQueue(), policy)
    val oldPolicy: ConnectionPool.AddressPolicy?
    synchronized(this) {
      oldPolicy = policies[address]?.policy
      policies = policies + (address to state)
    }

    val newConnectionsNeeded =
      policy.minimumConcurrentCalls - (oldPolicy?.minimumConcurrentCalls ?: 0)
    if (newConnectionsNeeded > 0) {
      state.schedule()
    } else if (newConnectionsNeeded < 0) {
      scheduleConnectionCloser()
    }
  }

  fun scheduleConnectionOpener() {
    policies.values.forEach { it.schedule() }
  }

  fun scheduleConnectionCloser() {
    cleanupQueue.schedule(cleanupTask)
  }

  /**
   * Ensure enough connections open to [address] to satisfy its [ConnectionPool.AddressPolicy].
   * If there are already enough connections, we're done.
   * If not, we create one and then schedule the task to run again immediately.
   */
  private fun ensureMinimumConnections(state: MinimumConnectionState): Long {
    // This policy does not require minimum connections, don't run again
    if (state.policy.minimumConcurrentCalls < 1) return -1

    var unsatisfiedCountMinTask = state.policy.minimumConcurrentCalls

    for (connection in connections) {
      synchronized(connection) {
        if (connection.isEligible(state.address, null)) {
          unsatisfiedCountMinTask -= connection.allocationLimit
        }
      }

      // The policy was satisfied by existing connections, don't run again
      if (unsatisfiedCountMinTask < 1) return -1
    }

    // If we got here then the policy was not satisfied -- open a connection!
    try {
      val connection = exchangeFinderFactory(this, state.address, user).find()

      // RealRoutePlanner will add the connection to the pool itself, other RoutePlanners may not
      // TODO: make all RoutePlanners consistent in this behavior
      if (connection !in connections) {
        synchronized(connection) { put(connection) }
      }

      return 0 // run again immediately to create more connections if needed
    } catch (ex: Exception) {
      // No need to log, user.connectFailed() will already have been called. Just try again later.
      return state.policy.backoffDelayMillis.jitterBy(state.policy.backoffJitterMillis) * 1_000_000
    }
  }

  private fun Long.jitterBy(amount: Int): Long {
    return this + ThreadLocalRandom.current().nextInt(amount * -1, amount)
  }

  class MinimumConnectionState(
    val address: Address,
    val queue: TaskQueue,
    var policy: ConnectionPool.AddressPolicy,
  ) {
    /**
     * This field is only ever accessed by the cleanup task, and it tracks
     * how many concurrent calls are already satisfied by existing connections.
     */
    var unsatisfiedCountCleanupTask: Int = 0
  }

  companion object {
    fun get(connectionPool: ConnectionPool): RealConnectionPool = connectionPool.delegate
  }
}
