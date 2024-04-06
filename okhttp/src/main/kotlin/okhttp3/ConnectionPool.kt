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
package okhttp3

import java.util.concurrent.TimeUnit
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.connection.FastFallbackExchangeFinder
import okhttp3.internal.connection.ForceConnectRoutePlanner
import okhttp3.internal.connection.RealConnectionPool
import okhttp3.internal.connection.RealRoutePlanner
import okhttp3.internal.connection.RouteDatabase

/**
 * Manages reuse of HTTP and HTTP/2 connections for reduced network latency. HTTP requests that
 * share the same [Address] may share a [Connection]. This class implements the policy
 * of which connections to keep open for future use.
 *
 * @constructor Create a new connection pool with tuning parameters appropriate for a single-user
 * application. The tuning parameters in this pool are subject to change in future OkHttp releases.
 * Currently this pool holds up to 5 idle connections which will be evicted after 5 minutes of
 * inactivity.
 */
class ConnectionPool internal constructor(
  internal val delegate: RealConnectionPool,
) {
  internal constructor(
    maxIdleConnections: Int = 5,
    keepAliveDuration: Long = 5,
    timeUnit: TimeUnit = TimeUnit.MINUTES,
    taskRunner: TaskRunner = TaskRunner.INSTANCE,
    connectionListener: ConnectionListener = ConnectionListener.NONE,
    readTimeoutMillis: Int = 10_000,
    writeTimeoutMillis: Int = 10_000,
    socketConnectTimeoutMillis: Int = 10_000,
    socketReadTimeoutMillis: Int = 10_000,
    pingIntervalMillis: Int = 10_000,
    retryOnConnectionFailure: Boolean = true,
    fastFallback: Boolean = true,
    routeDatabase: RouteDatabase = RouteDatabase(),
  ) : this(
    RealConnectionPool(
      taskRunner = taskRunner,
      maxIdleConnections = maxIdleConnections,
      keepAliveDuration = keepAliveDuration,
      timeUnit = timeUnit,
      connectionListener = connectionListener,
      exchangeFinderFactory = { pool, address, user ->
        FastFallbackExchangeFinder(
          ForceConnectRoutePlanner(
            RealRoutePlanner(
              taskRunner = taskRunner,
              connectionPool = pool,
              readTimeoutMillis = readTimeoutMillis,
              writeTimeoutMillis = writeTimeoutMillis,
              socketConnectTimeoutMillis = socketConnectTimeoutMillis,
              socketReadTimeoutMillis = socketReadTimeoutMillis,
              pingIntervalMillis = pingIntervalMillis,
              retryOnConnectionFailure = retryOnConnectionFailure,
              fastFallback = fastFallback,
              address = address,
              routeDatabase = routeDatabase,
              connectionUser = user,
            ),
          ),
          taskRunner,
        )
      },
    ),
  )

  // Public API
  @ExperimentalOkHttpApi
  constructor(
    maxIdleConnections: Int = 5,
    keepAliveDuration: Long = 5,
    timeUnit: TimeUnit = TimeUnit.MINUTES,
    connectionListener: ConnectionListener = ConnectionListener.NONE,
  ) : this(
    taskRunner = TaskRunner.INSTANCE,
    maxIdleConnections = maxIdleConnections,
    keepAliveDuration = keepAliveDuration,
    timeUnit = timeUnit,
    connectionListener = connectionListener,
  )

  // Public API
  constructor(
    maxIdleConnections: Int,
    keepAliveDuration: Long,
    timeUnit: TimeUnit,
  ) : this(
    maxIdleConnections = maxIdleConnections,
    keepAliveDuration = keepAliveDuration,
    timeUnit = timeUnit,
    taskRunner = TaskRunner.INSTANCE,
    connectionListener = ConnectionListener.NONE,
  )

  constructor() : this(5, 5, TimeUnit.MINUTES)

  /** Returns the number of idle connections in the pool. */
  fun idleConnectionCount(): Int = delegate.idleConnectionCount()

  /** Returns total number of connections in the pool. */
  fun connectionCount(): Int = delegate.connectionCount()

  internal val connectionListener: ConnectionListener
    get() = delegate.connectionListener

  /** Close and remove all idle connections in the pool. */
  fun evictAll() {
    delegate.evictAll()
  }

  /**
   * Sets a policy that applies to [address].
   * Overwrites any existing policy for that address.
   */
  @ExperimentalOkHttpApi
  fun setPolicy(
    address: Address,
    policy: AddressPolicy,
  ) {
    delegate.setPolicy(address, policy)
  }

  /**
   * A policy for how the pool should treat a specific address.
   */
  class AddressPolicy(
    /**
     * How many concurrent calls should be possible to make at any time.
     * The pool will routinely try to pre-emptively open connections to satisfy this minimum.
     * Connections will still be closed if they idle beyond the keep-alive but will be replaced.
     */
    @JvmField val minimumConcurrentCalls: Int = 0,
    /** How long to wait to retry pre-emptive connection attempts that fail. */
    @JvmField val backoffDelayMillis: Long = 60 * 1000,
    /** How much jitter to introduce in connection retry backoff delays */
    @JvmField val backoffJitterMillis: Int = 100,
  )
}
