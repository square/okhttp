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

import com.squareup.okhttp.internal.http.HttpConnection;
import com.squareup.okhttp.internal.http.HttpEngine;
import com.squareup.okhttp.internal.http.HttpTransport;
import com.squareup.okhttp.internal.http.RouteException;
import com.squareup.okhttp.internal.http.SocketConnector;
import com.squareup.okhttp.internal.http.SpdyTransport;
import com.squareup.okhttp.internal.http.Transport;
import com.squareup.okhttp.internal.spdy.SpdyConnection;
import java.io.IOException;
import java.net.Socket;
import java.net.UnknownServiceException;
import java.util.List;

/**
 * The sockets and streams of an HTTP, HTTPS, or HTTPS+SPDY connection. May be
 * used for multiple HTTP request/response exchanges. Connections may be direct
 * to the origin server or via a proxy.
 *
 * <p>Typically instances of this class are created, connected and exercised
 * automatically by the HTTP client. Applications may use this class to monitor
 * HTTP connections as members of a {@linkplain ConnectionPool connection pool}.
 *
 * <p>Do not confuse this class with the misnamed {@code HttpURLConnection},
 * which isn't so much a connection as a single request/response exchange.
 *
 * <h3>Modern TLS</h3>
 * There are tradeoffs when selecting which options to include when negotiating
 * a secure connection to a remote host. Newer TLS options are quite useful:
 * <ul>
 *   <li>Server Name Indication (SNI) enables one IP address to negotiate secure
 *       connections for multiple domain names.
 *   <li>Application Layer Protocol Negotiation (ALPN) enables the HTTPS port
 *       (443) to be used for different HTTP and SPDY protocols.
 * </ul>
 * Unfortunately, older HTTPS servers refuse to connect when such options are
 * presented. Rather than avoiding these options entirely, this class allows a
 * connection to be attempted with modern options and then retried without them
 * should the attempt fail.
 */
public final class Connection {
  private final ConnectionPool pool;
  private final Route route;

  private Socket socket;
  private boolean connected = false;
  private HttpConnection httpConnection;
  private SpdyConnection spdyConnection;
  private Protocol protocol = Protocol.HTTP_1_1;
  private long idleStartTimeNs;
  private Handshake handshake;
  private int recycleCount;

  /**
   * The object that owns this connection. Null if it is shared (for SPDY),
   * belongs to a pool, or has been discarded. Guarded by {@code pool}, which
   * clears the owner when an incoming connection is recycled.
   */
  private Object owner;

  public Connection(ConnectionPool pool, Route route) {
    this.pool = pool;
    this.route = route;
  }

  Object getOwner() {
    synchronized (pool) {
      return owner;
    }
  }

  void setOwner(Object owner) {
    if (isSpdy()) return; // SPDY connections are shared.
    synchronized (pool) {
      if (this.owner != null) throw new IllegalStateException("Connection already has an owner!");
      this.owner = owner;
    }
  }

  /**
   * Attempts to clears the owner of this connection. Returns true if the owner
   * was cleared and the connection can be pooled or reused. This will return
   * false if the connection cannot be pooled or reused, such as if it was
   * closed with {@link #closeIfOwnedBy}.
   */
  boolean clearOwner() {
    synchronized (pool) {
      if (owner == null) {
        // No owner? Don't reuse this connection.
        return false;
      }

      owner = null;
      return true;
    }
  }

  /**
   * Closes this connection if it is currently owned by {@code owner}. This also
   * strips the ownership of the connection so it cannot be pooled or reused.
   */
  void closeIfOwnedBy(Object owner) throws IOException {
    if (isSpdy()) throw new IllegalStateException();
    synchronized (pool) {
      if (this.owner != owner) {
        return; // Wrong owner. Perhaps a late disconnect?
      }

      this.owner = null; // Drop the owner so the connection won't be reused.
    }

    // Don't close() inside the synchronized block.
    socket.close();
  }

  void connect(int connectTimeout, int readTimeout, int writeTimeout, Request request,
      List<ConnectionSpec> connectionSpecs, boolean connectionRetryEnabled) throws RouteException {
    if (connected) throw new IllegalStateException("already connected");

    SocketConnector socketConnector = new SocketConnector(this, pool);
    SocketConnector.ConnectedSocket connectedSocket;
    if (route.address.getSslSocketFactory() != null) {
      // https:// communication
      connectedSocket = socketConnector.connectTls(connectTimeout, readTimeout, writeTimeout,
          request, route, connectionSpecs, connectionRetryEnabled);
    } else {
      // http:// communication.
      if (!connectionSpecs.contains(ConnectionSpec.CLEARTEXT)) {
        throw new RouteException(
            new UnknownServiceException(
                "CLEARTEXT communication not supported: " + connectionSpecs));
      }
      connectedSocket = socketConnector.connectCleartext(connectTimeout, readTimeout, route);
    }

    socket = connectedSocket.socket;
    handshake = connectedSocket.handshake;
    protocol = connectedSocket.alpnProtocol == null
        ? Protocol.HTTP_1_1 : connectedSocket.alpnProtocol;

    try {
      if (protocol == Protocol.SPDY_3 || protocol == Protocol.HTTP_2) {
        socket.setSoTimeout(0); // SPDY timeouts are set per-stream.
        spdyConnection = new SpdyConnection.Builder(route.address.uriHost, true, socket)
            .protocol(protocol).build();
        spdyConnection.sendConnectionPreface();
      } else {
        httpConnection = new HttpConnection(pool, this, socket);
      }
    } catch (IOException e) {
      throw new RouteException(e);
    }
    connected = true;
  }

  /**
   * Connects this connection if it isn't already. This creates tunnels, shares
   * the connection with the connection pool, and configures timeouts.
   */
  void connectAndSetOwner(OkHttpClient client, Object owner, Request request)
      throws RouteException {
    setOwner(owner);

    if (!isConnected()) {
      List<ConnectionSpec> connectionSpecs = route.address.getConnectionSpecs();
      connect(client.getConnectTimeout(), client.getReadTimeout(), client.getWriteTimeout(),
          request, connectionSpecs, client.getRetryOnConnectionFailure());
      if (isSpdy()) {
        client.getConnectionPool().share(this);
      }
      client.routeDatabase().connected(getRoute());
    }

    setTimeouts(client.getReadTimeout(), client.getWriteTimeout());
  }

  /** Returns true if {@link #connect} has been attempted on this connection. */
  boolean isConnected() {
    return connected;
  }

  /** Returns the route used by this connection. */
  public Route getRoute() {
    return route;
  }

  /**
   * Returns the socket that this connection uses, or null if the connection
   * is not currently connected.
   */
  public Socket getSocket() {
    return socket;
  }

  /** Returns true if this connection is alive. */
  boolean isAlive() {
    return !socket.isClosed() && !socket.isInputShutdown() && !socket.isOutputShutdown();
  }

  /**
   * Returns true if we are confident that we can read data from this
   * connection. This is more expensive and more accurate than {@link
   * #isAlive()}; callers should check {@link #isAlive()} first.
   */
  boolean isReadable() {
    if (httpConnection != null) return httpConnection.isReadable();
    return true; // SPDY connections, and connections before connect() are both optimistic.
  }

  void resetIdleStartTime() {
    if (spdyConnection != null) throw new IllegalStateException("spdyConnection != null");
    this.idleStartTimeNs = System.nanoTime();
  }

  /** Returns true if this connection is idle. */
  boolean isIdle() {
    return spdyConnection == null || spdyConnection.isIdle();
  }

  /**
   * Returns the time in ns when this connection became idle. Undefined if
   * this connection is not idle.
   */
  long getIdleStartTimeNs() {
    return spdyConnection == null ? idleStartTimeNs : spdyConnection.getIdleStartTimeNs();
  }

  public Handshake getHandshake() {
    return handshake;
  }

  /** Returns the transport appropriate for this connection. */
  Transport newTransport(HttpEngine httpEngine) throws IOException {
    return (spdyConnection != null)
        ? new SpdyTransport(httpEngine, spdyConnection)
        : new HttpTransport(httpEngine, httpConnection);
  }

  /**
   * Returns true if this is a SPDY connection. Such connections can be used
   * in multiple HTTP requests simultaneously.
   */
  boolean isSpdy() {
    return spdyConnection != null;
  }

  /**
   * Returns the protocol negotiated by this connection, or {@link
   * Protocol#HTTP_1_1} if no protocol has been negotiated.
   */
  public Protocol getProtocol() {
    return protocol;
  }

  /**
   * Sets the protocol negotiated by this connection. Typically this is used
   * when an HTTP/1.1 request is sent and an HTTP/1.0 response is received.
   */
  void setProtocol(Protocol protocol) {
    if (protocol == null) throw new IllegalArgumentException("protocol == null");
    this.protocol = protocol;
  }

  void setTimeouts(int readTimeoutMillis, int writeTimeoutMillis)
      throws RouteException {
    if (!connected) throw new IllegalStateException("setTimeouts - not connected");

    // Don't set timeouts on shared SPDY connections.
    if (httpConnection != null) {
      try {
        socket.setSoTimeout(readTimeoutMillis);
      } catch (IOException e) {
        throw new RouteException(e);
      }
      httpConnection.setTimeouts(readTimeoutMillis, writeTimeoutMillis);
    }
  }

  void incrementRecycleCount() {
    recycleCount++;
  }

  /**
   * Returns the number of times this connection has been returned to the
   * connection pool.
   */
  int recycleCount() {
    return recycleCount;
  }

  @Override public String toString() {
    return "Connection{"
        + route.address.uriHost + ":" + route.address.uriPort
        + ", proxy="
        + route.proxy
        + " hostAddress="
        + route.inetSocketAddress.getAddress().getHostAddress()
        + " cipherSuite="
        + (handshake != null ? handshake.cipherSuite() : "none")
        + " protocol="
        + protocol
        + '}';
  }
}
