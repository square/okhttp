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

import java.io.IOException
import java.net.Socket
import okhttp3.Address
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Route
import okhttp3.internal.canReuseConnectionFor
import okhttp3.internal.closeQuietly
import okhttp3.internal.connection.RoutePlanner.Plan
import okhttp3.internal.http.RealInterceptorChain
import okhttp3.internal.http2.ConnectionShutdownException
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException

internal class RealRoutePlanner(
  private val client: OkHttpClient,
  override val address: Address,
  private val call: RealCall,
  private val chain: RealInterceptorChain,
) : RoutePlanner {
  private val connectionPool = client.connectionPool.delegate
  private val eventListener = call.eventListener
  private val doExtensiveHealthChecks = chain.request.method != "GET"

  private var routeSelection: RouteSelector.Selection? = null
  private var routeSelector: RouteSelector? = null
  private var refusedStreamCount = 0
  private var connectionShutdownCount = 0
  private var otherFailureCount = 0
  private var nextRouteToTry: Route? = null

  override fun isCanceled(): Boolean = call.isCanceled()

  @Throws(IOException::class)
  override fun plan(): Plan {
    val reuseCallConnection = planReuseCallConnection()
    if (reuseCallConnection != null) return reuseCallConnection

    // We need a new connection. Give it fresh stats.
    refusedStreamCount = 0
    connectionShutdownCount = 0
    otherFailureCount = 0

    // Attempt to get a connection from the pool.
    val pooled1 = planReusePooledConnection()
    if (pooled1 != null) return pooled1

    // Do blocking calls to plan a route for a new connection.
    val connect = planConnect()

    // Now that we have a set of IP addresses, make another attempt at getting a connection from
    // the pool. We have a better chance of matching thanks to connection coalescing.
    val pooled2 = planReusePooledConnection(connect.connection, connect.routes)
    if (pooled2 != null) return pooled2

    return connect
  }

  /**
   * Returns the connection already attached to the call if it's eligible for a new exchange.
   *
   * If the call's connection exists and is eligible for another exchange, it is returned. If it
   * exists but cannot be used for another exchange, it is closed and this returns null.
   */
  private fun planReuseCallConnection(): ReusePlan? {
    // This may be mutated by releaseConnectionNoEvents()!
    val candidate = call.connection ?: return null

    // Make sure this connection is healthy & eligible for new exchanges. If it's no longer needed
    // then we're on the hook to close it.
    val healthy = candidate.isHealthy(doExtensiveHealthChecks)
    val toClose: Socket? = synchronized(candidate) {
      when {
        !healthy -> {
          candidate.noNewExchanges = true
          call.releaseConnectionNoEvents()
        }
        candidate.noNewExchanges || !sameHostAndPort(candidate.route().address.url) -> {
          call.releaseConnectionNoEvents()
        }
        else -> null
      }
    }

    // If the call's connection wasn't released, reuse it. We don't call connectionAcquired() here
    // because we already acquired it.
    if (call.connection != null) {
      check(toClose == null)
      return ReusePlan(candidate)
    }

    // The call's connection was released.
    toClose?.closeQuietly()
    eventListener.connectionReleased(call, candidate)
    return null
  }

  /** Plans to make a new connection by deciding which route to try next. */
  @Throws(IOException::class)
  private fun planConnect(): RealConnectPlan {
    // Use a route from a preceding coalesced connection.
    val localNextRouteToTry = nextRouteToTry
    if (localNextRouteToTry != null) {
      nextRouteToTry = null
      return RealConnectPlan(localNextRouteToTry)
    }

    // Use a route from an existing route selection.
    val existingRouteSelection = routeSelection
    if (existingRouteSelection != null && existingRouteSelection.hasNext()) {
      return RealConnectPlan(existingRouteSelection.next())
    }

    // Decide which proxy to use, if any. This may block in ProxySelector.select().
    var newRouteSelector = routeSelector
    if (newRouteSelector == null) {
      newRouteSelector = RouteSelector(
        address = address,
        routeDatabase = call.client.routeDatabase,
        call = call,
        fastFallback = client.fastFallback,
        eventListener = eventListener
      )
      routeSelector = newRouteSelector
    }

    // List available IP addresses for the current proxy. This may block in Dns.lookup().
    if (!newRouteSelector.hasNext()) throw IOException("exhausted all routes")
    val newRouteSelection = newRouteSelector.next()
    routeSelection = newRouteSelection

    if (call.isCanceled()) throw IOException("Canceled")

    return RealConnectPlan(newRouteSelection.next(), newRouteSelection.routes)
  }

  /**
   * Returns a plan to reuse a pooled connection, or null if the pool doesn't have a connection for
   * this address.
   *
   * If [connectionToReplace] is non-null, this will swap it for a pooled connection if that
   * pooled connection uses HTTP/2. That results in fewer sockets overall and thus fewer TCP slow
   * starts.
   */
  private fun planReusePooledConnection(
    connectionToReplace: RealConnection? = null,
    routes: List<Route>? = null,
  ): ReusePlan? {
    val result = connectionPool.callAcquirePooledConnection(
      doExtensiveHealthChecks = doExtensiveHealthChecks,
      address = address,
      call = call,
      routes = routes,
      requireMultiplexed = connectionToReplace != null && !connectionToReplace.isNew
    ) ?: return null

    // If we coalesced our connection, remember the replaced connection's route. That way if the
    // coalesced connection later fails we don't waste a valid route.
    if (connectionToReplace != null) {
      nextRouteToTry = connectionToReplace.route()
      if (!connectionToReplace.isNew) {
        connectionToReplace.socket().closeQuietly()
      }
    }

    eventListener.connectionAcquired(call, result)
    return ReusePlan(result)
  }

  /** Reuse an existing connection. */
  internal class ReusePlan(
    val connection: RealConnection,
  ) : Plan {

    override val isConnected: Boolean = true

    override fun connect() {
      error("already connected")
    }

    override fun handleSuccess() = connection

    override fun cancel() {
      error("unexpected cancel of reused connection")
    }
  }

  /** Establish a new connection. */
  internal inner class RealConnectPlan(
    route: Route,
    val routes: List<Route>? = null,
  ) : Plan {
    val connection = RealConnection(client.taskRunner, connectionPool, route)

    override val isConnected: Boolean
      get() = !connection.isNew

    @Throws(IOException::class)
    override fun connect() {
      // Tell the call about the connecting call so async cancels work.
      call.connectionsToCancel += connection
      try {
        connection.connect(
          connectTimeout = chain.connectTimeoutMillis,
          readTimeout = chain.readTimeoutMillis,
          writeTimeout = chain.writeTimeoutMillis,
          pingIntervalMillis = client.pingIntervalMillis,
          connectionRetryEnabled = client.retryOnConnectionFailure,
          call = call,
          eventListener = eventListener,
        )
      } finally {
        call.connectionsToCancel -= connection
      }
    }

    /** Returns the connection to use, which might be different from [connection]. */
    override fun handleSuccess(): RealConnection {
      call.client.routeDatabase.connected(connection.route())

      // If we raced another call connecting to this host, coalesce the connections. This makes for
      // 3 different lookups in the connection pool!
      val pooled3 = planReusePooledConnection(connection, routes)
      if (pooled3 != null) return pooled3.connection

      synchronized(connection) {
        connectionPool.put(connection)
        call.acquireConnectionNoEvents(connection)
      }

      eventListener.connectionAcquired(call, connection)
      return connection
    }

    override fun cancel() {
      connection.cancel()
    }
  }

  override fun trackFailure(e: IOException) {
    if (e is StreamResetException && e.errorCode == ErrorCode.REFUSED_STREAM) {
      refusedStreamCount++
    } else if (e is ConnectionShutdownException) {
      connectionShutdownCount++
    } else {
      otherFailureCount++
    }
  }

  override fun hasFailure(): Boolean {
    return refusedStreamCount > 0 || connectionShutdownCount > 0 || otherFailureCount > 0
  }

  override fun hasMoreRoutes(): Boolean {
    if (nextRouteToTry != null) {
      return true
    }

    val retryRoute = retryRoute()
    if (retryRoute != null) {
      // Lock in the route because retryRoute() is racy and we don't want to call it twice.
      nextRouteToTry = retryRoute
      return true
    }

    // If we have a routes left, use 'em.
    if (routeSelection?.hasNext() == true) return true

    // If we haven't initialized the route selector yet, assume it'll have at least one route.
    val localRouteSelector = routeSelector ?: return true

    // If we do have a route selector, use its routes.
    return localRouteSelector.hasNext()
  }

  /**
   * Return the route from the current connection if it should be retried, even if the connection
   * itself is unhealthy. The biggest gotcha here is that we shouldn't reuse routes from coalesced
   * connections.
   */
  private fun retryRoute(): Route? {
    if (refusedStreamCount > 1 || connectionShutdownCount > 1 || otherFailureCount > 0) {
      return null // This route has too many problems to retry.
    }

    val connection = call.connection ?: return null

    synchronized(connection) {
      if (connection.routeFailureCount != 0) return null
      if (!connection.noNewExchanges) return null // This route is still in use.
      if (!connection.route().address.url.canReuseConnectionFor(address.url)) return null
      return connection.route()
    }
  }

  override fun sameHostAndPort(url: HttpUrl): Boolean {
    val routeUrl = address.url
    return url.port == routeUrl.port && url.host == routeUrl.host
  }
}
