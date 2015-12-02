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
package com.squareup.okhttp;

import com.squareup.okhttp.internal.RouteDatabase;
import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.io.RealConnection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * Manages reuse of HTTP and SPDY connections for reduced network latency. HTTP
 * requests that share the same {@link com.squareup.okhttp.Address} may share a
 * {@link Connection}. This class implements the policy of which connections to
 * keep open for future use.
 *
 * <p>The {@link #getDefault() system-wide default} uses system properties for
 * tuning parameters:
 * <ul>
 *     <li>{@code http.keepAlive} true if HTTP and SPDY connections should be
 *         pooled at all. Default is true.
 *     <li>{@code http.maxConnections} maximum number of idle connections to
 *         each to keep in the pool. Default is 5.
 *     <li>{@code http.keepAliveDuration} Time in milliseconds to keep the
 *         connection alive in the pool before closing it. Default is 5 minutes.
 *         This property isn't used by {@code HttpURLConnection}.
 * </ul>
 *
 * <p>The default instance <i>doesn't</i> adjust its configuration as system
 * properties are changed. This assumes that the applications that set these
 * parameters do so before making HTTP connections, and that this class is
 * initialized lazily.
 */
public final class ConnectionPool {
  private static final long DEFAULT_KEEP_ALIVE_DURATION_MS = 5 * 60 * 1000; // 5 min

  private static final ConnectionPool systemDefault;

  static {
    String keepAlive = System.getProperty("http.keepAlive");
    String keepAliveDuration = System.getProperty("http.keepAliveDuration");
    String maxIdleConnections = System.getProperty("http.maxConnections");
    long keepAliveDurationMs = keepAliveDuration != null ? Long.parseLong(keepAliveDuration)
        : DEFAULT_KEEP_ALIVE_DURATION_MS;
    if (keepAlive != null && !Boolean.parseBoolean(keepAlive)) {
      systemDefault = new ConnectionPool(0, keepAliveDurationMs);
    } else if (maxIdleConnections != null) {
      systemDefault = new ConnectionPool(Integer.parseInt(maxIdleConnections), keepAliveDurationMs);
    } else {
      systemDefault = new ConnectionPool(5, keepAliveDurationMs);
    }
  }

  /** The maximum number of idle connections for each address. */
  private final int maxIdleConnections;
  private final long keepAliveDurationNs;

  private final Deque<RealConnection> connections = new ArrayDeque<>();
  final RouteDatabase routeDatabase = new RouteDatabase();

  public ConnectionPool(int maxIdleConnections, long keepAliveDurationMs) {
    this.maxIdleConnections = maxIdleConnections;
    this.keepAliveDurationNs = keepAliveDurationMs * 1000 * 1000;
  }

  public static ConnectionPool getDefault() {
    return systemDefault;
  }

  /** Returns the number of idle connections in the pool. */
  public synchronized int getIdleConnectionCount() {
    int total = 0;
    for (RealConnection connection : connections) {
      if (connection.allocationCount == 0) total++;
    }
    return total;
  }

  /**
   * Returns total number of connections in the pool. Note that prior to OkHttp 2.7 this included
   * only idle connections and SPDY connections. In OkHttp 2.7 this includes all connections, both
   * active and inactive. Use {@link #getIdleConnectionCount()} to count connections not currently
   * in use.
   */
  public synchronized int getConnectionCount() {
    return connections.size();
  }

  /** @deprecated Use {@link #getMultiplexedConnectionCount()}. */
  @Deprecated
  public synchronized int getSpdyConnectionCount() {
    return getMultiplexedConnectionCount();
  }

  /** Returns total number of multiplexed connections in the pool. */
  public synchronized int getMultiplexedConnectionCount() {
    int total = 0;
    for (RealConnection connection : connections) {
      if (connection.isMultiplexed()) total++;
    }
    return total;
  }

  /** Returns total number of http connections in the pool. */
  public synchronized int getHttpConnectionCount() {
    return connections.size() - getMultiplexedConnectionCount();
  }

  /** Returns a recycled connection to {@code address}, or null if no such connection exists. */
  public synchronized Connection get(Address address) {
    for (RealConnection connection : connections) {
      // TODO(jwilson): this is awkward. We're already holding a lock on 'this', and
      //     connection.allocationLimit() may also lock the FramedConnection.
      if (connection.allocationCount < connection.allocationLimit()
          && address.equals(connection.getRoute().address)
          && !connection.noNewStreams) {
        connection.allocationCount++;
        return connection;
      }
    }
    return null;
  }

  // TODO(jwilson): reduce visibility.
  public synchronized void put(RealConnection connection) {
    connections.add(connection);
  }

  // TODO(jwilson): reduce visibility.
  public synchronized void remove(RealConnection connection) {
    connections.remove(connection);
  }

  /** Close and remove all idle connections in the pool. */
  public void evictAll() {
    List<RealConnection> evictedConnections = new ArrayList<>();
    synchronized (this) {
      for (Iterator<RealConnection> i = connections.iterator(); i.hasNext(); ) {
        RealConnection connection = i.next();
        if (connection.allocationCount == 0) {
          connection.noNewStreams = true;
          evictedConnections.add(connection);
          i.remove();
        }
      }
    }

    for (RealConnection connection : evictedConnections) {
      Util.closeQuietly(connection.getSocket());
    }
  }
}
