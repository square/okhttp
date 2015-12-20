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
package okhttp3.internal.http;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import okhttp3.Address;
import okhttp3.ConnectionPool;
import okhttp3.Route;
import okhttp3.internal.Internal;
import okhttp3.internal.RouteDatabase;
import okhttp3.internal.Util;
import okhttp3.internal.io.RealConnection;
import okio.Sink;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * This class coordinates the relationship between three entities:
 *
 * <ul>
 *   <li><strong>Connections:</strong> physical socket connections to remote servers. These are
 *       potentially slow to establish so it is necessary to be able to cancel a connection
 *       currently being connected.
 *   <li><strong>Streams:</strong> logical HTTP request/response pairs that are layered on
 *       connections. Each connection has its own allocation limit, which defines how many
 *       concurrent streams that connection can carry. HTTP/1.x connections can carry 1 stream
 *       at a time, SPDY and HTTP/2 typically carry multiple.
 *   <li><strong>Calls:</strong> a logical sequence of streams, typically an initial request and
 *       its follow up requests. We prefer to keep all streams of a single call on the same
 *       connection for better behavior and locality.
 * </ul>
 *
 * <p>Instances of this class act on behalf of the call, using one or more streams over one or
 * more connections. This class has APIs to release each of the above resources:
 *
 * <ul>
 *   <li>{@link #noNewStreams()} prevents the connection from being used for new streams in the
 *       future. Use this after a {@code Connection: close} header, or when the connection may be
 *       inconsistent.
 *   <li>{@link #streamFinished streamFinished()} releases the active stream from this allocation.
 *       Note that only one stream may be active at a given time, so it is necessary to call {@link
 *       #streamFinished streamFinished()} before creating a subsequent stream with {@link
 *       #newStream newStream()}.
 *   <li>{@link #release()} removes the call's hold on the connection. Note that this won't
 *       immediately free the connection if there is a stream still lingering. That happens when a
 *       call is complete but its response body has yet to be fully consumed.
 * </ul>
 *
 * <p>This class supports {@linkplain #cancel asynchronous canceling}. This is intended to have
 * the smallest blast radius possible. If an HTTP/2 stream is active, canceling will cancel that
 * stream but not the other streams sharing its connection. But if the TLS handshake is still in
 * progress then canceling may break the entire connection.
 */
public final class StreamAllocation {
  public final Address address;
  private Route route;
  private final ConnectionPool connectionPool;

  // State guarded by connectionPool.
  private RouteSelector routeSelector;
  private RealConnection connection;
  private boolean released;
  private boolean canceled;
  private HttpStream stream;

  public StreamAllocation(ConnectionPool connectionPool, Address address) {
    this.connectionPool = connectionPool;
    this.address = address;
    this.routeSelector = new RouteSelector(address, routeDatabase());
  }

  public HttpStream newStream(int connectTimeout, int readTimeout, int writeTimeout,
      boolean connectionRetryEnabled, boolean doExtensiveHealthChecks)
      throws RouteException, IOException {
    try {
      RealConnection resultConnection = findHealthyConnection(connectTimeout, readTimeout,
          writeTimeout, connectionRetryEnabled, doExtensiveHealthChecks);

      HttpStream resultStream;
      if (resultConnection.framedConnection != null) {
        resultStream = new Http2xStream(this, resultConnection.framedConnection);
      } else {
        resultConnection.getSocket().setSoTimeout(readTimeout);
        resultConnection.source.timeout().timeout(readTimeout, MILLISECONDS);
        resultConnection.sink.timeout().timeout(writeTimeout, MILLISECONDS);
        resultStream = new Http1xStream(this, resultConnection.source, resultConnection.sink);
      }

      synchronized (connectionPool) {
        stream = resultStream;
        return resultStream;
      }
    } catch (IOException e) {
      throw new RouteException(e);
    }
  }

  /**
   * Finds a connection and returns it if it is healthy. If it is unhealthy the process is repeated
   * until a healthy connection is found.
   */
  private RealConnection findHealthyConnection(int connectTimeout, int readTimeout,
      int writeTimeout, boolean connectionRetryEnabled, boolean doExtensiveHealthChecks)
      throws IOException, RouteException {
    while (true) {
      RealConnection candidate = findConnection(
          connectTimeout, readTimeout, writeTimeout, connectionRetryEnabled);
      if (connection.isHealthy(doExtensiveHealthChecks)) {
        return candidate;
      }
      connectionFailed(new IOException());
    }
  }

  /**
   * Returns a connection to host a new stream. This prefers the existing connection if it exists,
   * then the pool, finally building a new connection.
   */
  private RealConnection findConnection(int connectTimeout, int readTimeout, int writeTimeout,
      boolean connectionRetryEnabled) throws IOException, RouteException {
    Route selectedRoute;
    synchronized (connectionPool) {
      if (released) throw new IllegalStateException("released");
      if (stream != null) throw new IllegalStateException("stream != null");
      if (canceled) throw new IOException("Canceled");

      RealConnection allocatedConnection = this.connection;
      if (allocatedConnection != null && !allocatedConnection.noNewStreams) {
        return allocatedConnection;
      }

      // Attempt to get a connection from the pool.
      RealConnection pooledConnection = Internal.instance.get(connectionPool, address, this);
      if (pooledConnection != null) {
        this.connection = pooledConnection;
        return pooledConnection;
      }

      selectedRoute = route;
    }

    if (selectedRoute == null) {
      selectedRoute = routeSelector.next();
      synchronized (connectionPool) {
        route = selectedRoute;
      }
    }
    RealConnection newConnection = new RealConnection(selectedRoute);
    acquire(newConnection);

    synchronized (connectionPool) {
      Internal.instance.put(connectionPool, newConnection);
      this.connection = newConnection;
      if (canceled) throw new IOException("Canceled");
    }

    newConnection.connect(connectTimeout, readTimeout, writeTimeout, address.connectionSpecs(),
        connectionRetryEnabled);
    routeDatabase().connected(newConnection.getRoute());

    return newConnection;
  }

  public void streamFinished(boolean noNewStreams, HttpStream stream) {
    synchronized (connectionPool) {
      if (stream == null || stream != this.stream) {
        throw new IllegalStateException("expected " + this.stream + " but was " + stream);
      }
      if (!noNewStreams) {
        connection.successCount++;
      }
    }
    deallocate(noNewStreams, false, true);
  }

  public HttpStream stream() {
    synchronized (connectionPool) {
      return stream;
    }
  }

  private RouteDatabase routeDatabase() {
    return Internal.instance.routeDatabase(connectionPool);
  }

  public synchronized RealConnection connection() {
    return connection;
  }

  public void release() {
    deallocate(false, true, false);
  }

  /** Forbid new streams from being created on the connection that hosts this allocation. */
  public void noNewStreams() {
    deallocate(true, false, false);
  }

  /**
   * Releases resources held by this allocation. If sufficient resources are allocated, the
   * connection will be detached or closed.
   */
  private void deallocate(boolean noNewStreams, boolean released, boolean streamFinished) {
    RealConnection connectionToClose = null;
    synchronized (connectionPool) {
      if (streamFinished) {
        this.stream = null;
      }
      if (released) {
        this.released = true;
      }
      if (connection != null) {
        if (noNewStreams) {
          connection.noNewStreams = true;
        }
        if (this.stream == null && (this.released || connection.noNewStreams)) {
          release(connection);
          if (connection.allocations.isEmpty()) {
            connection.idleAtNanos = System.nanoTime();
            if (Internal.instance.connectionBecameIdle(connectionPool, connection)) {
              connectionToClose = connection;
            }
          }
          connection = null;
        }
      }
    }
    if (connectionToClose != null) {
      Util.closeQuietly(connectionToClose.getSocket());
    }
  }

  public void cancel() {
    HttpStream streamToCancel;
    RealConnection connectionToCancel;
    synchronized (connectionPool) {
      canceled = true;
      streamToCancel = stream;
      connectionToCancel = connection;
    }
    if (streamToCancel != null) {
      streamToCancel.cancel();
    } else if (connectionToCancel != null) {
      connectionToCancel.cancel();
    }
  }

  public void connectionFailed(IOException e) {
    synchronized (connectionPool) {
      // Avoid this route if it's never seen a successful call.
      if (connection != null && connection.successCount == 0) {
        if (route != null && e != null) {
          routeSelector.connectFailed(route, e);
        }
        route = null;
      }
    }
    deallocate(true, false, true);
  }

  /**
   * Use this allocation to hold {@code connection}. Each call to this must be paired with a call to
   * {@link #release} on the same connection.
   */
  public void acquire(RealConnection connection) {
    connection.allocations.add(new WeakReference<>(this));
  }

  /** Remove this allocation from the connection's list of allocations. */
  private void release(RealConnection connection) {
    for (int i = 0, size = connection.allocations.size(); i < size; i++) {
      Reference<StreamAllocation> reference = connection.allocations.get(i);
      if (reference.get() == this) {
        connection.allocations.remove(i);
        return;
      }
    }
    throw new IllegalStateException();
  }

  public boolean recover(IOException e, Sink requestBodyOut) {
    if (connection != null) {
      connectionFailed(e);
    }

    boolean canRetryRequestBody = requestBodyOut == null || requestBodyOut instanceof RetryableSink;
    if ((routeSelector != null && !routeSelector.hasNext()) // No more routes to attempt.
        || !isRecoverable(e)
        || !canRetryRequestBody) {
      return false;
    }

    return true;
  }

  private boolean isRecoverable(IOException e) {
    // If there was a protocol problem, don't recover.
    if (e instanceof ProtocolException) {
      return false;
    }

    // If there was an interruption don't recover, but if there was a timeout
    // we should try the next route (if there is one).
    if (e instanceof InterruptedIOException) {
      return e instanceof SocketTimeoutException;
    }

    // Look for known client-side or negotiation errors that are unlikely to be fixed by trying
    // again with a different route.
    if (e instanceof SSLHandshakeException) {
      // If the problem was a CertificateException from the X509TrustManager,
      // do not retry.
      if (e.getCause() instanceof CertificateException) {
        return false;
      }
    }
    if (e instanceof SSLPeerUnverifiedException) {
      // e.g. a certificate pinning error.
      return false;
    }

    // An example of one we might want to retry with a different route is a problem connecting to a
    // proxy and would manifest as a standard IOException. Unless it is one we know we should not
    // retry, we return true and try a new route.
    return true;
  }

  @Override public String toString() {
    return address.toString();
  }
}
