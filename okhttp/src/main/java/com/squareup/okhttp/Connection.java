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
import com.squareup.okhttp.internal.http.HttpAuthenticator;
import com.squareup.okhttp.internal.http.HttpConnection;
import com.squareup.okhttp.internal.http.HttpEngine;
import com.squareup.okhttp.internal.http.HttpTransport;
import com.squareup.okhttp.internal.http.SpdyTransport;
import com.squareup.okhttp.internal.spdy.SpdyConnection;
import java.io.Closeable;
import java.io.IOException;
import java.net.Proxy;
import java.net.Socket;
import javax.net.ssl.SSLSocket;
import okio.ByteString;

import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;

/**
 * The sockets and streams of an HTTP, HTTPS, or HTTPS+SPDY connection. May be
 * used for multiple HTTP request/response exchanges. Connections may be direct
 * to the origin server or via a proxy.
 *
 * <p>Typically instances of this class are created, connected and exercised
 * automatically by the HTTP client. Applications may use this class to monitor
 * HTTP connections as members of a {@link ConnectionPool connection pool}.
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
 *   <li>Next Protocol Negotiation (NPN) enables the HTTPS port (443) to be used
 *       for both HTTP and SPDY protocols.
 * </ul>
 * Unfortunately, older HTTPS servers refuse to connect when such options are
 * presented. Rather than avoiding these options entirely, this class allows a
 * connection to be attempted with modern options and then retried without them
 * should the attempt fail.
 */
public final class Connection implements Closeable {
  private final ConnectionPool pool;
  private final Route route;

  private Socket socket;
  private boolean connected = false;
  private HttpConnection httpConnection;
  private SpdyConnection spdyConnection;
  private int httpMinorVersion = 1; // Assume HTTP/1.1
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

  public Object getOwner() {
    synchronized (pool) {
      return owner;
    }
  }

  public void setOwner(Object owner) {
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
  public boolean clearOwner() {
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
  public void closeIfOwnedBy(Object owner) throws IOException {
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

  public void connect(int connectTimeout, int readTimeout, int writeTimeout,
      TunnelRequest tunnelRequest) throws IOException {
    if (connected) throw new IllegalStateException("already connected");

    if (route.proxy.type() != Proxy.Type.HTTP) {
      socket = new Socket(route.proxy);
    } else {
      socket = route.address.socketFactory.createSocket();
    }

    socket.setSoTimeout(readTimeout);
    Platform.get().connectSocket(socket, route.inetSocketAddress, connectTimeout);

    if (route.address.sslSocketFactory != null) {
      upgradeToTls(tunnelRequest, readTimeout, writeTimeout);
    } else {
      httpConnection = new HttpConnection(pool, this, socket, readTimeout, writeTimeout);
    }
    connected = true;
  }

  /**
   * Create an {@code SSLSocket} and perform the TLS handshake and certificate
   * validation.
   */
  private void upgradeToTls(TunnelRequest tunnelRequest, int readTimeout, int writeTimeout)
      throws IOException {
    Platform platform = Platform.get();

    // Make an SSL Tunnel on the first message pair of each SSL + proxy connection.
    if (requiresTunnel()) {
      makeTunnel(tunnelRequest, readTimeout, writeTimeout);
    }

    // Create the wrapper over connected socket.
    socket = route.address.sslSocketFactory
        .createSocket(socket, route.address.uriHost, route.address.uriPort, true /* autoClose */);
    SSLSocket sslSocket = (SSLSocket) socket;
    if (route.modernTls) {
      platform.enableTlsExtensions(sslSocket, route.address.uriHost);
    } else {
      platform.supportTlsIntolerantServer(sslSocket);
    }

    boolean useNpn = false;
    if (route.modernTls) {
      boolean http2 = route.address.protocols.contains(Protocol.HTTP_2);
      boolean spdy3 = route.address.protocols.contains(Protocol.SPDY_3);
      if (http2 && spdy3) {
        platform.setNpnProtocols(sslSocket, Protocol.HTTP2_SPDY3_AND_HTTP);
        useNpn = true;
      } else if (http2) {
        platform.setNpnProtocols(sslSocket, Protocol.HTTP2_AND_HTTP_11);
        useNpn = true;
      } else if (spdy3) {
        platform.setNpnProtocols(sslSocket, Protocol.SPDY3_AND_HTTP11);
        useNpn = true;
      }
    }

    // Force handshake. This can throw!
    sslSocket.startHandshake();

    // Verify that the socket's certificates are acceptable for the target host.
    if (!route.address.hostnameVerifier.verify(route.address.uriHost, sslSocket.getSession())) {
      throw new IOException("Hostname '" + route.address.uriHost + "' was not verified");
    }

    handshake = Handshake.get(sslSocket.getSession());

    ByteString maybeProtocol;
    Protocol selectedProtocol = Protocol.HTTP_11;
    if (useNpn && (maybeProtocol = platform.getNpnSelectedProtocol(sslSocket)) != null) {
      selectedProtocol = Protocol.find(maybeProtocol); // Throws IOE on unknown.
    }

    if (selectedProtocol.spdyVariant) {
      sslSocket.setSoTimeout(0); // SPDY timeouts are set per-stream.
      spdyConnection = new SpdyConnection.Builder(route.address.getUriHost(), true, socket)
          .protocol(selectedProtocol).build();
      spdyConnection.sendConnectionHeader();
    } else {
      httpConnection = new HttpConnection(pool, this, socket, readTimeout, writeTimeout);
    }
  }

  /** Returns true if {@link #connect} has been attempted on this connection. */
  public boolean isConnected() {
    return connected;
  }

  @Override public void close() throws IOException {
    socket.close();
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
  public boolean isAlive() {
    return !socket.isClosed() && !socket.isInputShutdown() && !socket.isOutputShutdown();
  }

  /**
   * Returns true if we are confident that we can read data from this
   * connection. This is more expensive and more accurate than {@link
   * #isAlive()}; callers should check {@link #isAlive()} first.
   */
  public boolean isReadable() {
    if (httpConnection != null) return httpConnection.isReadable();
    return true; // SPDY connections, and connections before connect() are both optimistic.
  }

  public void resetIdleStartTime() {
    if (spdyConnection != null) throw new IllegalStateException("spdyConnection != null");
    this.idleStartTimeNs = System.nanoTime();
  }

  /** Returns true if this connection is idle. */
  public boolean isIdle() {
    return spdyConnection == null || spdyConnection.isIdle();
  }

  /**
   * Returns true if this connection has been idle for longer than
   * {@code keepAliveDurationNs}.
   */
  public boolean isExpired(long keepAliveDurationNs) {
    return getIdleStartTimeNs() < System.nanoTime() - keepAliveDurationNs;
  }

  /**
   * Returns the time in ns when this connection became idle. Undefined if
   * this connection is not idle.
   */
  public long getIdleStartTimeNs() {
    return spdyConnection == null ? idleStartTimeNs : spdyConnection.getIdleStartTimeNs();
  }

  public Handshake getHandshake() {
    return handshake;
  }

  /** Returns the transport appropriate for this connection. */
  public Object newTransport(HttpEngine httpEngine) throws IOException {
    return (spdyConnection != null)
        ? new SpdyTransport(httpEngine, spdyConnection)
        : new HttpTransport(httpEngine, httpConnection);
  }

  /**
   * Returns true if this is a SPDY connection. Such connections can be used
   * in multiple HTTP requests simultaneously.
   */
  public boolean isSpdy() {
    return spdyConnection != null;
  }

  /**
   * Returns the minor HTTP version that should be used for future requests on
   * this connection. Either 0 for HTTP/1.0, or 1 for HTTP/1.1. The default
   * value is 1 for new connections.
   */
  public int getHttpMinorVersion() {
    return httpMinorVersion;
  }

  public void setHttpMinorVersion(int httpMinorVersion) {
    this.httpMinorVersion = httpMinorVersion;
  }

  /**
   * Returns true if the HTTP connection needs to tunnel one protocol over
   * another, such as when using HTTPS through an HTTP proxy. When doing so,
   * we must avoid buffering bytes intended for the higher-level protocol.
   */
  public boolean requiresTunnel() {
    return route.address.sslSocketFactory != null && route.proxy.type() == Proxy.Type.HTTP;
  }

  public void updateReadTimeout(int newTimeout) throws IOException {
    if (!connected) throw new IllegalStateException("updateReadTimeout - not connected");
    socket.setSoTimeout(newTimeout);
  }

  public void incrementRecycleCount() {
    recycleCount++;
  }

  /**
   * Returns the number of times this connection has been returned to the
   * connection pool.
   */
  public int recycleCount() {
    return recycleCount;
  }

  /**
   * To make an HTTPS connection over an HTTP proxy, send an unencrypted
   * CONNECT request to create the proxy connection. This may need to be
   * retried if the proxy requires authorization.
   */
  private void makeTunnel(TunnelRequest tunnelRequest, int readTimeout, int writeTimeout)
      throws IOException {
    HttpConnection tunnelConnection = new HttpConnection(
        pool, this, socket, readTimeout, writeTimeout);
    Request request = tunnelRequest.getRequest();
    String requestLine = tunnelRequest.requestLine();
    while (true) {
      tunnelConnection.writeRequest(request.headers(), requestLine);
      tunnelConnection.flush();
      Response response = tunnelConnection.readResponse().request(request).build();
      tunnelConnection.emptyResponseBody();

      switch (response.code()) {
        case HTTP_OK:
          // Assume the server won't send a TLS ServerHello until we send a TLS ClientHello. If that
          // happens, then we will have buffered bytes that are needed by the SSLSocket!
          if (tunnelConnection.bufferSize() > 0) {
            throw new IOException("TLS tunnel buffered too many bytes!");
          }
          return;

        case HTTP_PROXY_AUTH:
          request = HttpAuthenticator.processAuthHeader(
              route.address.authenticator, response, route.proxy);
          if (request != null) continue;
          throw new IOException("Failed to authenticate with proxy");

        default:
          throw new IOException(
              "Unexpected response code for CONNECT: " + response.code());
      }
    }
  }
}
