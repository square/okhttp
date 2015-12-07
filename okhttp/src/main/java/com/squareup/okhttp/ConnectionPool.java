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
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
    long keepAliveDurationMs = keepAliveDuration != null
        ? Long.parseLong(keepAliveDuration)
        : DEFAULT_KEEP_ALIVE_DURATION_MS;
    if (keepAlive != null && !Boolean.parseBoolean(keepAlive)) {
      systemDefault = new ConnectionPool(0, keepAliveDurationMs);
    } else if (maxIdleConnections != null) {
      systemDefault = new ConnectionPool(Integer.parseInt(maxIdleConnections), keepAliveDurationMs);
    } else {
      systemDefault = new ConnectionPool(5, keepAliveDurationMs);
    }
  }

  /**
   * A background thread is used to cleanup expired connections. There will be, at most, a single
   * thread running per connection pool. We use a thread pool executor because it can shrink to
   * zero threads, permitting this pool to be garbage collected.
   */
  private final Executor executor = new ThreadPoolExecutor(
      0 /* corePoolSize */, 1 /* maximumPoolSize */, 60L /* keepAliveTime */, TimeUnit.SECONDS,
      new LinkedBlockingQueue<Runnable>(), Util.threadFactory("OkHttp ConnectionPool", true));

  /** The maximum number of idle connections for each address. */
  private final int maxIdleConnections;
  private final long keepAliveDurationNs;
  private Runnable cleanupRunnable = new Runnable() {
    @Override public void run() {
      while (true) {
        long waitNanos = cleanup(System.nanoTime());
        if (waitNanos == -1) return;
        if (waitNanos > 0) {
          long waitMillis = waitNanos / 1000000L;
          waitNanos -= (waitMillis * 1000000L);
          synchronized (ConnectionPool.this) {
            try {
              ConnectionPool.this.wait(waitMillis, (int) waitNanos);
            } catch (InterruptedException ignored) {
            }
          }
        }
      }
    }
  };

  private final Deque<RealConnection> connections = new ArrayDeque<>();
  final RouteDatabase routeDatabase = new RouteDatabase();

  public ConnectionPool(int maxIdleConnections, long keepAliveDurationMs) {
    this(maxIdleConnections, keepAliveDurationMs, TimeUnit.MILLISECONDS);
  }

  public ConnectionPool(int maxIdleConnections, long keepAliveDuration, TimeUnit timeUnit) {
    this.maxIdleConnections = maxIdleConnections;
    this.keepAliveDurationNs = timeUnit.toNanos(keepAliveDuration);

    // Put a floor on the keep alive duration, otherwise cleanup will spin loop.
    if (keepAliveDuration <= 0) {
      throw new IllegalArgumentException("keepAliveDuration <= 0: " + keepAliveDuration);
    }
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
    if (connections.isEmpty()) {
      executor.execute(cleanupRunnable);
    }
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

  /**
   * Performs maintenance on this pool, evicting connections that have expired.
   *
   * <p>Returns the duration in nanos to sleep until the next scheduled call to this method.
   * Returns -1 if no further cleanups are required.
   */
  long cleanup(long now) {
    int inUseConnectionCount = 0;
    long nanosUntilNextCleanup = -1L;
    RealConnection connectionToEvict = null;

    // Find either a connection to evict, or the time that the next eviction is due.
    synchronized (this) {
      for (Iterator<RealConnection> i = connections.iterator(); i.hasNext(); ) {
        RealConnection connection = i.next();

        // If the connection is in use, keep searching.
        if (connection.allocationCount > 0) {
          inUseConnectionCount++;
          nanosUntilNextCleanup = keepAliveDurationNs;
          continue;
        }

        // If the connection is ready to be evicted, we're done.
        long evictAtNanos = connection.idleAtNanos + keepAliveDurationNs;
        long nanosUntilEviction = evictAtNanos - now;
        if (nanosUntilEviction <= 0) {
          connection.noNewStreams = true;
          connectionToEvict = connection;
          i.remove();
          break;
        }

        // Is this the next connection to evict?
        if (nanosUntilNextCleanup == -1L || nanosUntilNextCleanup > nanosUntilEviction) {
          nanosUntilNextCleanup = nanosUntilEviction;
        }
      }
    }

    if (connectionToEvict != null) {
      Util.closeQuietly(connectionToEvict.getSocket());
      // Cleanup again immediately.
      return 0;
    } else if (nanosUntilNextCleanup != -1) {
      // A connection will be ready to evict soon.
      return nanosUntilNextCleanup;
    } else if (inUseConnectionCount != 0) {
      // All connections are in use. It'll be at least the keep alive duration 'til we run again.
      return keepAliveDurationNs;
    } else {
      // No connections, idle or in use.
      return -1;
    }
  }

  void setCleanupRunnableForTest(Runnable cleanupRunnable) {
    this.cleanupRunnable = cleanupRunnable;
  }
}
