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

import com.squareup.okhttp.internal.Platform;
import com.squareup.okhttp.internal.Util;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Manages reuse of HTTP and SPDY connections for reduced network latency. HTTP
 * requests that share the same {@link com.squareup.okhttp.Address} may share a
 * {@link com.squareup.okhttp.Connection}. This class implements the policy of
 * which connections to keep open for future use.
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
  private static final int MAX_CONNECTIONS_TO_CLEANUP = 2;
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

  private final LinkedList<Connection> connections = new LinkedList<>();

  /**
   * A background thread is used to cleanup expired connections. There will be, at most, a single
   * thread running per connection pool.
   *
   * <p>A {@link ThreadPoolExecutor} is used and not a
   * {@link java.util.concurrent.ScheduledThreadPoolExecutor}; ScheduledThreadPoolExecutors do not
   * shrink. This executor shrinks the thread pool after a period of inactivity, and starts threads
   * as needed. Delays are instead handled by the {@link #connectionsCleanupRunnable}. It is
   * important that the {@link #connectionsCleanupRunnable} stops eventually, otherwise it will pin
   * the thread, and thus the connection pool, in memory.
   */
  private Executor executor = new ThreadPoolExecutor(
      0 /* corePoolSize */, 1 /* maximumPoolSize */, 60L /* keepAliveTime */, TimeUnit.SECONDS,
      new LinkedBlockingQueue<Runnable>(), Util.threadFactory("OkHttp ConnectionPool", true));

  /** {@code true} if the pool is actively draining, {@code false} if it is currently empty. */
  private boolean draining;

  private final Runnable connectionsCleanupRunnable = new Runnable() {
    // An executing connectionsCleanupRunnable keeps a reference to the enclosing ConnectionPool,
    // preventing the ConnectionPool from being garbage collected before all held connections have
    // been explicitly closed. If this was not the case any open connections in the pool would
    // trigger StrictMode violations in Android when they were garbage collected. http://b/18369687
    @Override public void run() {
      while (true) {
        performCleanup();

        // See whether this runnable should continue executing.
        synchronized(ConnectionPool.this) {
          if (connections.size() == 0) {
            draining = false;
            return;
          }
        }

        // Pause to avoid checking the pool too regularly, which would drain the battery on mobile
        // devices.
        try {
          // Use the keep alive duration as a rough indicator of a good check interval.
          long keepAliveDurationMillis = keepAliveDurationNs / (1000 * 1000);
          Thread.sleep(keepAliveDurationMillis);
        } catch (InterruptedException e) {
          // Ignored.
        }
      }
    }
  };

  public ConnectionPool(int maxIdleConnections, long keepAliveDurationMs) {
    this.maxIdleConnections = maxIdleConnections;
    this.keepAliveDurationNs = keepAliveDurationMs * 1000 * 1000;
  }

  public static ConnectionPool getDefault() {
    return systemDefault;
  }

  /** Returns total number of connections in the pool. */
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
    for (Connection connection : connections) {
      if (connection.isSpdy()) total++;
    }
    return total;
  }

  /** Returns total number of http connections in the pool. */
  public synchronized int getHttpConnectionCount() {
    return connections.size() - getMultiplexedConnectionCount();
  }

  /** Returns a recycled connection to {@code address}, or null if no such connection exists. */
  public synchronized Connection get(Address address) {
    Connection foundConnection = null;
    for (ListIterator<Connection> i = connections.listIterator(connections.size());
        i.hasPrevious(); ) {
      Connection connection = i.previous();
      if (!connection.getRoute().getAddress().equals(address)
          || !connection.isAlive()
          || System.nanoTime() - connection.getIdleStartTimeNs() >= keepAliveDurationNs) {
        continue;
      }
      i.remove();
      if (!connection.isSpdy()) {
        try {
          Platform.get().tagSocket(connection.getSocket());
        } catch (SocketException e) {
          Util.closeQuietly(connection.getSocket());
          // When unable to tag, skip recycling and close
          Platform.get().logW("Unable to tagSocket(): " + e);
          continue;
        }
      }
      foundConnection = connection;
      break;
    }

    if (foundConnection != null && foundConnection.isSpdy()) {
      connections.addFirst(foundConnection); // Add it back after iteration.
      scheduleCleanupAsRequired();
    }

    return foundConnection;
  }

  /**
   * Gives {@code connection} to the pool. The pool may store the connection,
   * or close it, as its policy describes.
   *
   * <p>It is an error to use {@code connection} after calling this method.
   */
  void recycle(Connection connection) {
    if (connection.isSpdy()) {
      return;
    }

    if (!connection.clearOwner()) {
      return; // This connection isn't eligible for reuse.
    }

    if (!connection.isAlive()) {
      Util.closeQuietly(connection.getSocket());
      return;
    }

    try {
      Platform.get().untagSocket(connection.getSocket());
    } catch (SocketException e) {
      // When unable to remove tagging, skip recycling and close.
      Platform.get().logW("Unable to untagSocket(): " + e);
      Util.closeQuietly(connection.getSocket());
      return;
    }

    synchronized (this) {
      connections.addFirst(connection);
      connection.incrementRecycleCount();
      connection.resetIdleStartTime();
      scheduleCleanupAsRequired();
    }
  }

  /**
   * Shares the SPDY connection with the pool. Callers to this method may
   * continue to use {@code connection}.
   */
  void share(Connection connection) {
    if (!connection.isSpdy()) throw new IllegalArgumentException();
    if (connection.isAlive()) {
      synchronized (this) {
        connections.addFirst(connection);
        scheduleCleanupAsRequired();
      }
    }
  }

  /** Close and remove all connections in the pool. */
  public void evictAll() {
    List<Connection> connections;
    synchronized (this) {
      connections = new ArrayList<>(this.connections);
      this.connections.clear();
    }

    for (int i = 0, size = connections.size(); i < size; i++) {
      Util.closeQuietly(connections.get(i).getSocket());
    }
  }

  // Callers must synchronize on "this".
  private void scheduleCleanupAsRequired() {
    if (!draining) {
      // A new connection has potentially been offered up to an empty / drained pool.
      // Start the clean-up immediately.
      draining = true;
      executor.execute(connectionsCleanupRunnable);
    }
  }

  /** Performs a single round of pool cleanup. */
  // VisibleForTesting
  void performCleanup() {
    List<Connection>expiredConnections = new ArrayList<>(MAX_CONNECTIONS_TO_CLEANUP);
    int idleConnectionCount = 0;
    synchronized (this) {
      for (ListIterator<Connection> i = connections.listIterator(connections.size());
          i.hasPrevious(); ) {
        Connection connection = i.previous();
        if (!connection.isAlive() || connection.isExpired(keepAliveDurationNs)) {
          i.remove();
          expiredConnections.add(connection);
          if (expiredConnections.size() == MAX_CONNECTIONS_TO_CLEANUP) {
            break;
          }
        } else if (connection.isIdle()) {
          idleConnectionCount++;
        }
      }

      for (ListIterator<Connection> i = connections.listIterator(connections.size());
          i.hasPrevious() && idleConnectionCount > maxIdleConnections; ) {
        Connection connection = i.previous();
        if (connection.isIdle()) {
          expiredConnections.add(connection);
          i.remove();
          --idleConnectionCount;
        }
      }
    }

    for (Connection expiredConnection : expiredConnections) {
      Util.closeQuietly(expiredConnection.getSocket());
    }
  }

  /**
   * Replace the default {@link Executor} with a different one. Only use in tests.
   */
  // VisibleForTesting
  void replaceCleanupExecutorForTests(Executor cleanupExecutor) {
    this.executor = cleanupExecutor;
  }

  /**
   * Returns a snapshot of the connections in this pool, ordered from newest to
   * oldest. Only use in tests.
   */
  List<Connection> getConnections() {
    synchronized (this) {
      return new ArrayList<>(connections);
    }
  }
}
