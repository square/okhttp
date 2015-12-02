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
package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.Address;
import com.squareup.okhttp.ConnectionPool;
import com.squareup.okhttp.Route;
import com.squareup.okhttp.internal.Internal;
import com.squareup.okhttp.internal.RouteDatabase;
import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.io.RealConnection;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
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
        resultConnection.streamCount++;
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
      deallocate(true, false, true);
    }
  }

  /**
   * Returns a connection to host a new stream. This prefers the existing connection if it exists,
   * then the pool, finally building a new connection.
   */
  private RealConnection findConnection(int connectTimeout, int readTimeout, int writeTimeout,
      boolean connectionRetryEnabled) throws IOException, RouteException {
    synchronized (connectionPool) {
      if (released) throw new IllegalStateException("released");
      if (stream != null) throw new IllegalStateException("stream != null");
      if (canceled) throw new IOException("Canceled");

      RealConnection allocatedConnection = this.connection;
      if (allocatedConnection != null && !allocatedConnection.noNewStreams) {
        return allocatedConnection;
      }
    }

    // Attempt to get a connection from the pool.
    RealConnection pooledConnection = (RealConnection) connectionPool.get(address);
    if (pooledConnection != null) {
      synchronized (connectionPool) {
        this.connection = pooledConnection;
        if (canceled) throw new IOException("Canceled");
        return pooledConnection;
      }
    }

    // Attempt to create a connection.
    synchronized (connectionPool) {
      if (routeSelector == null) {
        routeSelector = new RouteSelector(address, routeDatabase());
      }
    }
    Route route = routeSelector.next();
    RealConnection newConnection = new RealConnection(route);
    newConnection.allocationCount = 1;

    synchronized (connectionPool) {
      connectionPool.put(newConnection);
      this.connection = newConnection;
      if (canceled) throw new IOException("Canceled");
    }

    newConnection.connect(connectTimeout, readTimeout, writeTimeout, address.getConnectionSpecs(),
        connectionRetryEnabled);
    routeDatabase().connected(newConnection.getRoute());

    return newConnection;
  }

  public void streamFinished(HttpStream stream) {
    synchronized (connectionPool) {
      if (stream == null || stream != this.stream) {
        throw new IllegalStateException("expected " + this.stream + " but was " + stream);
      }
    }
    deallocate(false, false, true);
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
  public void noNewStreamsOnConnection() {
    deallocate(true, false, false);
  }

  /** Forbid new streams from being created on this allocation. */
  public void noNewStreams() {
    // TODO(jwilson): fix this for HTTP/2 to not nuke the socket connection.
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
          connection.allocationCount--;
          if (connection.streamCount > 0) {
            routeSelector = null;
          }
          if (connection.noNewStreams && connection.allocationCount == 0) {
            connectionPool.remove(connection);
            connectionToClose = connection;
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

  private void connectionFailed(IOException e) {
    synchronized (connectionPool) {
      if (routeSelector != null) {
        if (connection.streamCount == 0) {
          // Record the failure on a fresh route.
          Route failedRoute = connection.getRoute();
          routeSelector.connectFailed(failedRoute, e);
        } else {
          // We saw a failure on a recycled connection, reset this allocation with a fresh route.
          routeSelector = null;
        }
      }
    }
    deallocate(true, false, true);
  }

  public boolean recover(RouteException e) {
    if (connection != null) {
      connectionFailed(e.getLastConnectException());
    }

    if ((routeSelector != null && !routeSelector.hasNext()) // No more routes to attempt.
        || !isRecoverable(e)) {
      return false;
    }

    return true;
  }

  public boolean recover(IOException e, Sink requestBodyOut) {
    if (connection != null) {
      int streamCount = connection.streamCount;
      connectionFailed(e);

      if (streamCount == 1) {
        // This isn't a recycled connection.
        // TODO(jwilson): find a better way for this.
        return false;
      }
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

    // If there was an interruption or timeout, don't recover.
    if (e instanceof InterruptedIOException) {
      return false;
    }

    return true;
  }

  private boolean isRecoverable(RouteException e) {
    // Problems with a route may mean the connection can be retried with a new route, or may
    // indicate a client-side or server-side issue that should not be retried. To tell, we must look
    // at the cause.

    IOException ioe = e.getLastConnectException();

    // If there was a protocol problem, don't recover.
    if (ioe instanceof ProtocolException) {
      return false;
    }

    // If there was an interruption don't recover, but if there was a timeout
    // we should try the next route (if there is one).
    if (ioe instanceof InterruptedIOException) {
      return ioe instanceof SocketTimeoutException;
    }

    // Look for known client-side or negotiation errors that are unlikely to be fixed by trying
    // again with a different route.
    if (ioe instanceof SSLHandshakeException) {
      // If the problem was a CertificateException from the X509TrustManager,
      // do not retry.
      if (ioe.getCause() instanceof CertificateException) {
        return false;
      }
    }
    if (ioe instanceof SSLPeerUnverifiedException) {
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
