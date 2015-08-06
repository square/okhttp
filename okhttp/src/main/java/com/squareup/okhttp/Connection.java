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

import com.squareup.okhttp.internal.ConnectionSpecSelector;
import com.squareup.okhttp.internal.Platform;
import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.framed.FramedConnection;
import com.squareup.okhttp.internal.http.FramedTransport;
import com.squareup.okhttp.internal.http.HttpConnection;
import com.squareup.okhttp.internal.http.HttpEngine;
import com.squareup.okhttp.internal.http.HttpTransport;
import com.squareup.okhttp.internal.http.OkHeaders;
import com.squareup.okhttp.internal.http.RouteException;
import com.squareup.okhttp.internal.http.Transport;
import com.squareup.okhttp.internal.tls.OkHostnameVerifier;
import java.io.IOException;
import java.net.Proxy;
import java.net.Socket;
import java.net.UnknownServiceException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Source;

import static com.squareup.okhttp.internal.Util.closeQuietly;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;

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
  private FramedConnection framedConnection;
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
    if (isFramed()) return; // Framed connections are shared.
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
    if (isFramed()) throw new IllegalStateException();
    synchronized (pool) {
      if (this.owner != owner) {
        return; // Wrong owner. Perhaps a late disconnect?
      }

      this.owner = null; // Drop the owner so the connection won't be reused.
    }

    // Don't close() inside the synchronized block.
    if (socket != null) {
      socket.close();
    }
  }

  void connect(int connectTimeout, int readTimeout, int writeTimeout, Request request,
      List<ConnectionSpec> connectionSpecs, boolean connectionRetryEnabled) throws RouteException {
    if (connected) throw new IllegalStateException("already connected");

    RouteException routeException = null;
    ConnectionSpecSelector connectionSpecSelector = new ConnectionSpecSelector(connectionSpecs);
    Proxy proxy = route.getProxy();
    Address address = route.getAddress();

    if (route.address.getSslSocketFactory() == null
        && !connectionSpecs.contains(ConnectionSpec.CLEARTEXT)) {
      throw new RouteException(new UnknownServiceException(
          "CLEARTEXT communication not supported: " + connectionSpecs));
    }

    while (!connected) {
      try {
        socket = proxy.type() == Proxy.Type.DIRECT || proxy.type() == Proxy.Type.HTTP
            ? address.getSocketFactory().createSocket()
            : new Socket(proxy);
        connectSocket(connectTimeout, readTimeout, writeTimeout, request,
            connectionSpecSelector);
        connected = true; // Success!
      } catch (IOException e) {
        Util.closeQuietly(socket);
        socket = null;

        if (routeException == null) {
          routeException = new RouteException(e);
        } else {
          routeException.addConnectException(e);
        }

        if (!connectionRetryEnabled || !connectionSpecSelector.connectionFailed(e)) {
          throw routeException;
        }
      }
    }
  }

  /** Does all the work necessary to build a full HTTP or HTTPS connection on a raw socket. */
  private void connectSocket(int connectTimeout, int readTimeout, int writeTimeout,
      Request request, ConnectionSpecSelector connectionSpecSelector) throws IOException {
    socket.setSoTimeout(readTimeout);
    Platform.get().connectSocket(socket, route.getSocketAddress(), connectTimeout);

    if (route.address.getSslSocketFactory() != null) {
      connectTls(readTimeout, writeTimeout, request, connectionSpecSelector);
    }

    if (protocol == Protocol.SPDY_3 || protocol == Protocol.HTTP_2) {
      socket.setSoTimeout(0); // Framed connection timeouts are set per-stream.
      framedConnection = new FramedConnection.Builder(route.address.uriHost, true, socket)
          .protocol(protocol).build();
      framedConnection.sendConnectionPreface();
    } else {
      httpConnection = new HttpConnection(pool, this, socket);
    }
  }

  private void connectTls(int readTimeout, int writeTimeout, Request request,
      ConnectionSpecSelector connectionSpecSelector) throws IOException {
    if (route.requiresTunnel()) {
      createTunnel(readTimeout, writeTimeout, request);
    }

    Address address = route.getAddress();
    SSLSocketFactory sslSocketFactory = address.getSslSocketFactory();
    boolean success = false;
    SSLSocket sslSocket = null;
    try {
      // Create the wrapper over the connected socket.
      sslSocket = (SSLSocket) sslSocketFactory.createSocket(
          socket, address.getUriHost(), address.getUriPort(), true /* autoClose */);

      // Configure the socket's ciphers, TLS versions, and extensions.
      ConnectionSpec connectionSpec = connectionSpecSelector.configureSecureSocket(sslSocket);
      if (connectionSpec.supportsTlsExtensions()) {
        Platform.get().configureTlsExtensions(
            sslSocket, address.getUriHost(), address.getProtocols());
      }

      // Force handshake. This can throw!
      sslSocket.startHandshake();
      Handshake unverifiedHandshake = Handshake.get(sslSocket.getSession());

      // Verify that the socket's certificates are acceptable for the target host.
      if (!address.getHostnameVerifier().verify(address.getUriHost(), sslSocket.getSession())) {
        X509Certificate cert = (X509Certificate) unverifiedHandshake.peerCertificates().get(0);
        throw new SSLPeerUnverifiedException("Hostname " + address.getUriHost() + " not verified:"
            + "\n    certificate: " + CertificatePinner.pin(cert)
            + "\n    DN: " + cert.getSubjectDN().getName()
            + "\n    subjectAltNames: " + OkHostnameVerifier.allSubjectAltNames(cert));
      }

      // Check that the certificate pinner is satisfied by the certificates presented.
      address.getCertificatePinner().check(address.getUriHost(),
          unverifiedHandshake.peerCertificates());

      // Success! Save the handshake and the ALPN protocol.
      String maybeProtocol = connectionSpec.supportsTlsExtensions()
          ? Platform.get().getSelectedProtocol(sslSocket)
          : null;
      protocol = maybeProtocol != null
          ? Protocol.get(maybeProtocol)
          : Protocol.HTTP_1_1;
      handshake = unverifiedHandshake;
      socket = sslSocket;
      success = true;
    } finally {
      if (sslSocket != null) {
        Platform.get().afterHandshake(sslSocket);
      }
      if (!success) {
        closeQuietly(sslSocket);
      }
    }
  }

  /**
   * To make an HTTPS connection over an HTTP proxy, send an unencrypted
   * CONNECT request to create the proxy connection. This may need to be
   * retried if the proxy requires authorization.
   */
  private void createTunnel(int readTimeout, int writeTimeout, Request request) throws IOException {
    // Make an SSL Tunnel on the first message pair of each SSL + proxy connection.
    Request tunnelRequest = createTunnelRequest(request);
    HttpConnection tunnelConnection = new HttpConnection(pool, this, socket);
    tunnelConnection.setTimeouts(readTimeout, writeTimeout);
    HttpUrl url = tunnelRequest.httpUrl();
    String requestLine = "CONNECT " + url.host() + ":" + url.port() + " HTTP/1.1";
    while (true) {
      tunnelConnection.writeRequest(tunnelRequest.headers(), requestLine);
      tunnelConnection.flush();
      Response response = tunnelConnection.readResponse().request(tunnelRequest).build();
      // The response body from a CONNECT should be empty, but if it is not then we should consume
      // it before proceeding.
      long contentLength = OkHeaders.contentLength(response);
      if (contentLength == -1L) {
        contentLength = 0L;
      }
      Source body = tunnelConnection.newFixedLengthSource(contentLength);
      Util.skipAll(body, Integer.MAX_VALUE, TimeUnit.MILLISECONDS);
      body.close();

      switch (response.code()) {
        case HTTP_OK:
          // Assume the server won't send a TLS ServerHello until we send a TLS ClientHello. If
          // that happens, then we will have buffered bytes that are needed by the SSLSocket!
          // This check is imperfect: it doesn't tell us whether a handshake will succeed, just
          // that it will almost certainly fail because the proxy has sent unexpected data.
          if (tunnelConnection.bufferSize() > 0) {
            throw new IOException("TLS tunnel buffered too many bytes!");
          }
          return;

        case HTTP_PROXY_AUTH:
          tunnelRequest = OkHeaders.processAuthHeader(
              route.getAddress().getAuthenticator(), response, route.getProxy());
          if (tunnelRequest != null) continue;
          throw new IOException("Failed to authenticate with proxy");

        default:
          throw new IOException(
              "Unexpected response code for CONNECT: " + response.code());
      }
    }
  }

  /**
   * Returns a request that creates a TLS tunnel via an HTTP proxy, or null if
   * no tunnel is necessary. Everything in the tunnel request is sent
   * unencrypted to the proxy server, so tunnels include only the minimum set of
   * headers. This avoids sending potentially sensitive data like HTTP cookies
   * to the proxy unencrypted.
   */
  private Request createTunnelRequest(Request request) throws IOException {
    HttpUrl tunnelUrl = new HttpUrl.Builder()
        .scheme("https")
        .host(request.httpUrl().host())
        .port(request.httpUrl().port())
        .build();
    Request.Builder result = new Request.Builder()
        .url(tunnelUrl)
        .header("Host", Util.hostHeader(tunnelUrl))
        .header("Proxy-Connection", "Keep-Alive"); // For HTTP/1.0 proxies like Squid.

    // Copy over the User-Agent header if it exists.
    String userAgent = request.header("User-Agent");
    if (userAgent != null) {
      result.header("User-Agent", userAgent);
    }

    // Copy over the Proxy-Authorization header if it exists.
    String proxyAuthorization = request.header("Proxy-Authorization");
    if (proxyAuthorization != null) {
      result.header("Proxy-Authorization", proxyAuthorization);
    }

    return result.build();
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
      if (isFramed()) {
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

  BufferedSource rawSource() {
    if (httpConnection == null) throw new UnsupportedOperationException();
    return httpConnection.rawSource();
  }

  BufferedSink rawSink() {
    if (httpConnection == null) throw new UnsupportedOperationException();
    return httpConnection.rawSink();
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
    return true; // Framed connections, and connections before connect() are both optimistic.
  }

  void resetIdleStartTime() {
    if (framedConnection != null) throw new IllegalStateException("framedConnection != null");
    this.idleStartTimeNs = System.nanoTime();
  }

  /** Returns true if this connection is idle. */
  boolean isIdle() {
    return framedConnection == null || framedConnection.isIdle();
  }

  /**
   * Returns the time in ns when this connection became idle. Undefined if
   * this connection is not idle.
   */
  long getIdleStartTimeNs() {
    return framedConnection == null ? idleStartTimeNs : framedConnection.getIdleStartTimeNs();
  }

  public Handshake getHandshake() {
    return handshake;
  }

  /** Returns the transport appropriate for this connection. */
  Transport newTransport(HttpEngine httpEngine) throws IOException {
    return (framedConnection != null)
        ? new FramedTransport(httpEngine, framedConnection)
        : new HttpTransport(httpEngine, httpConnection);
  }

  /**
   * Returns true if this is a SPDY connection. Such connections can be used
   * in multiple HTTP requests simultaneously.
   */
  boolean isFramed() {
    return framedConnection != null;
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
