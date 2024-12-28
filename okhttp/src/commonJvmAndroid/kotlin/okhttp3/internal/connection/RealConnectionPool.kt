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
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import okhttp3.Address
import okhttp3.ConnectionListener
import okhttp3.ConnectionPool
import okhttp3.Route
import okhttp3.internal.assertHeld
import okhttp3.internal.closeQuietly
import okhttp3.internal.concurrent.Task
import okhttp3.internal.concurrent.TaskQueue
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.connection.Locks.withLock
import okhttp3.internal.connection.RealCall.CallReference
import okhttp3.internal.okHttpName
import okhttp3.internal.platform.Platform
import okio.IOException

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

  @Volatile
  private var addressStates: Map<Address, AddressState> = mapOf()

  private val cleanupQueue: TaskQueue = taskRunner.newQueue()
  private val cleanupTask =
    object : Task("$okHttpName ConnectionPool connection closer") {
      override fun runOnce(): Long = closeConnections(System.nanoTime())
    }

  private fun AddressState.scheduleOpener() {
    queue.schedule(
      object : Task("$okHttpName ConnectionPool connection opener") {
        override fun runOnce(): Long = openConnections(this@scheduleOpener)
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
      it.withLock { it.calls.isEmpty() }
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
        connection.withLock {
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
        connection.withLock {
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
    connection.lock.assertHeld()

    connections.add(connection)
//    connection.queueEvent { connectionListener.connectEnd(connection) }
    scheduleCloser()
  }

  /**
   * Notify this pool that [connection] has become idle. Returns true if the connection has been
   * removed from the pool and should be closed.
   */
  fun connectionBecameIdle(connection: RealConnection): Boolean {
    connection.lock.assertHeld()

    return if (connection.noNewExchanges || maxIdleConnections == 0) {
      connection.noNewExchanges = true
      connections.remove(connection)
      if (connections.isEmpty()) cleanupQueue.cancelAll()
      scheduleOpener(connection.route.address)
      true
    } else {
      scheduleCloser()
      false
    }
  }

  fun evictAll() {
    val i = connections.iterator()
    while (i.hasNext()) {
      val connection = i.next()
      val socketToClose =
        connection.withLock {
          if (connection.calls.isEmpty()) {
            i.remove()
            connection.noNewExchanges = true
            return@withLock connection.socket()
          } else {
            return@withLock null
          }
        }
      if (socketToClose != null) {
        socketToClose.closeQuietly()
        connectionListener.connectionClosed(connection)
      }
    }

    if (connections.isEmpty()) cleanupQueue.cancelAll()

    for (policy in addressStates.values) {
      policy.scheduleOpener()
    }
  }

  /**
   * Performs maintenance on this pool, evicting the connection that has been idle the longest if
   * either it has exceeded the keep alive limit or the idle connections limit.
   *
   * Returns the duration in nanoseconds to sleep until the next scheduled call to this method.
   * Returns -1 if no further cleanups are required.
   */
  fun closeConnections(now: Long): Long {
    // Compute the concurrent call capacity for each address. We won't close a connection if doing
    // so would violate a policy, unless it's OLD.
    val addressStates = this.addressStates
    for (state in addressStates.values) {
      state.concurrentCallCapacity = 0
    }
    for (connection in connections) {
      val addressState = addressStates[connection.route.address] ?: continue
      connection.withLock {
        addressState.concurrentCallCapacity += connection.allocationLimit
      }
    }

    // Find the longest-idle connections in 2 categories:
    //
    //  1. OLD: Connections that have been idle for at least keepAliveDurationNs. We close these if
    //     we find them, regardless of what the address policies need.
    //
    //  2. EVICTABLE: Connections not required by any address policy. This matches connections that
    //     don't participate in any policy, plus connections whose policies won't be violated if the
    //     connection is closed. We only close these if the idle connection limit is exceeded.
    //
    // Also count the evictable connections to find out if we must close an EVICTABLE connection
    // before its keepAliveDurationNs is reached.
    var earliestOldIdleAtNs = (now - keepAliveDurationNs) + 1
    var earliestOldConnection: RealConnection? = null
    var earliestEvictableIdleAtNs = Long.MAX_VALUE
    var earliestEvictableConnection: RealConnection? = null
    var inUseConnectionCount = 0
    var evictableConnectionCount = 0
    for (connection in connections) {
      connection.withLock {
        // If the connection is in use, keep searching.
        if (pruneAndGetAllocationCount(connection, now) > 0) {
          inUseConnectionCount++
          return@withLock
        }

        val idleAtNs = connection.idleAtNs

        if (idleAtNs < earliestOldIdleAtNs) {
          earliestOldIdleAtNs = idleAtNs
          earliestOldConnection = connection
        }

        if (isEvictable(addressStates, connection)) {
          evictableConnectionCount++
          if (idleAtNs < earliestEvictableIdleAtNs) {
            earliestEvictableIdleAtNs = idleAtNs
            earliestEvictableConnection = connection
          }
        }
      }
    }

    val toEvict: RealConnection?
    val toEvictIdleAtNs: Long
    when {
      // We had at least one OLD connection. Close the oldest one.
      earliestOldConnection != null -> {
        toEvict = earliestOldConnection
        toEvictIdleAtNs = earliestOldIdleAtNs
      }

      // We have too many EVICTABLE connections. Close the oldest one.
      evictableConnectionCount > maxIdleConnections -> {
        toEvict = earliestEvictableConnection
        toEvictIdleAtNs = earliestEvictableIdleAtNs
      }

      else -> {
        toEvict = null
        toEvictIdleAtNs = -1L
      }
    }

    when {
      toEvict != null -> {
        // We've chosen a connection to evict. Confirm it's still okay to be evicted, then close it.
        toEvict.withLock {
          if (toEvict.calls.isNotEmpty()) return 0L // No longer idle.
          if (toEvict.idleAtNs != toEvictIdleAtNs) return 0L // No longer oldest.
          toEvict.noNewExchanges = true
          connections.remove(toEvict)
        }
        addressStates[toEvict.route.address]?.scheduleOpener()
        toEvict.socket().closeQuietly()
        connectionListener.connectionClosed(toEvict)
        if (connections.isEmpty()) cleanupQueue.cancelAll()

        // Clean up again immediately.
        return 0L
      }

      earliestEvictableConnection != null -> {
        // A connection will be ready to evict soon.
        return earliestEvictableIdleAtNs + keepAliveDurationNs - now
      }

      inUseConnectionCount > 0 -> {
        // All connections are in use. It'll be at least the keep alive duration 'til we run again.
        return keepAliveDurationNs
      }

      else -> {
        // No connections, idle or in use.
        return -1
      }
    }
  }

  /** Returns true if no address policies prevent [connection] from being evicted. */
  private fun isEvictable(
    addressStates: Map<Address, AddressState>,
    connection: RealConnection,
  ): Boolean {
    val addressState = addressStates[connection.route.address] ?: return true
    val capacityWithoutIt = addressState.concurrentCallCapacity - connection.allocationLimit
    return capacityWithoutIt >= addressState.policy.minimumConcurrentCalls
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
    connection.lock.assertHeld()

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
    val state = AddressState(address, taskRunner.newQueue(), policy)
    val newConnectionsNeeded: Int

    while (true) {
      val oldMap = this.addressStates
      val newMap = oldMap + (address to state)
      if (addressStatesUpdater.compareAndSet(this, oldMap, newMap)) {
        val oldPolicyMinimumConcurrentCalls = oldMap[address]?.policy?.minimumConcurrentCalls ?: 0
        newConnectionsNeeded = policy.minimumConcurrentCalls - oldPolicyMinimumConcurrentCalls
        break
      }
    }

    when {
      newConnectionsNeeded > 0 -> state.scheduleOpener()
      newConnectionsNeeded < 0 -> scheduleCloser()
    }
  }

  /** Open connections to [address], if required by the address policy. */
  fun scheduleOpener(address: Address) {
    addressStates[address]?.scheduleOpener()
  }

  fun scheduleCloser() {
    cleanupQueue.schedule(cleanupTask)
  }

  /**
   * Ensure enough connections open to [address] to satisfy its [ConnectionPool.AddressPolicy].
   * If there are already enough connections, we're done.
   * If not, we create one and then schedule the task to run again immediately.
   */
  private fun openConnections(state: AddressState): Long {
    // This policy does not require minimum connections, don't run again
    if (state.policy.minimumConcurrentCalls == 0) return -1L

    var concurrentCallCapacity = 0
    for (connection in connections) {
      if (state.address != connection.route.address) continue
      connection.withLock {
        concurrentCallCapacity += connection.allocationLimit
      }

      // The policy was satisfied by existing connections, don't run again
      if (concurrentCallCapacity >= state.policy.minimumConcurrentCalls) return -1L
    }

    // If we got here then the policy was not satisfied -- open a connection!
    try {
      val connection = exchangeFinderFactory(this, state.address, PoolConnectionUser).find()

      // RealRoutePlanner will add the connection to the pool itself, other RoutePlanners may not
      // TODO: make all RoutePlanners consistent in this behavior
      if (connection !in connections) {
        connection.withLock { put(connection) }
      }

      return 0L // run again immediately to create more connections if needed
    } catch (e: IOException) {
      // No need to log, user.connectFailed() will already have been called. Just try again later.
      return state.policy.backoffDelayMillis.jitterBy(state.policy.backoffJitterMillis) * 1_000_000
    }
  }

  private fun Long.jitterBy(amount: Int): Long {
    return this + ThreadLocalRandom.current().nextInt(amount * -1, amount)
  }

  class AddressState(
    val address: Address,
    val queue: TaskQueue,
    var policy: ConnectionPool.AddressPolicy,
  ) {
    /**
     * How many calls the pool can carry without opening new connections. This field must only be
     * accessed by the connection closer task.
     */
    var concurrentCallCapacity: Int = 0
  }

  companion object {
    fun get(connectionPool: ConnectionPool): RealConnectionPool = connectionPool.delegate

    private var addressStatesUpdater =
      AtomicReferenceFieldUpdater.newUpdater(
        RealConnectionPool::class.java,
        Map::class.java,
        "addressStates",
      )
  }
}
