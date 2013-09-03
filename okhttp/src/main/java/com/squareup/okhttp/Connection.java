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
import com.squareup.okhttp.internal.http.HttpEngine;
import com.squareup.okhttp.internal.http.HttpTransport;
import com.squareup.okhttp.internal.http.RawHeaders;
import com.squareup.okhttp.internal.http.SpdyTransport;
import com.squareup.okhttp.internal.spdy.SpdyConnection;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLSocket;

import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;

/**
 * Holds the sockets and streams of an HTTP, HTTPS, or HTTPS+SPDY connection,
 * which may be used for multiple HTTP request/response exchanges. Connections
 * may be direct to the origin server or via a proxy.
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
 * <li>Server Name Indication (SNI) enables one IP address to negotiate secure
 * connections for multiple domain names.
 * <li>Next Protocol Negotiation (NPN) enables the HTTPS port (443) to be used
 * for both HTTP and SPDY transports.
 * </ul>
 * Unfortunately, older HTTPS servers refuse to connect when such options are
 * presented. Rather than avoiding these options entirely, this class allows a
 * connection to be attempted with modern options and then retried without them
 * should the attempt fail.
 */
public final class Connection implements Closeable {
  private static final byte[] NPN_PROTOCOLS = new byte[] {
      17, 'H', 'T', 'T', 'P', '-', 'd', 'r', 'a', 'f', 't', '-', '0', '4', '/', '2', '.', '0',
      6, 's', 'p', 'd', 'y', '/', '3',
      8, 'h', 't', 't', 'p', '/', '1', '.', '1'
  };
  private static final byte[] HTTP2_DRAFT_04 = new byte[] {
      'H', 'T', 'T', 'P', '-', 'd', 'r', 'a', 'f', 't', '-', '0', '4', '/', '2', '.', '0'
  };
  private static final byte[] SPDY3 = new byte[] {
      's', 'p', 'd', 'y', '/', '3'
  };
  private static final byte[] HTTP_11 = new byte[] {
      'h', 't', 't', 'p', '/', '1', '.', '1'
  };

  private final Route route;

  private Socket socket;
  private InputStream in;
  private OutputStream out;
  private boolean connected = false;
  private SpdyConnection spdyConnection;
  private int httpMinorVersion = 1; // Assume HTTP/1.1
  private long idleStartTimeNs;

  public Connection(Route route) {
    this.route = route;
  }

  public void connect(int connectTimeout, int readTimeout, TunnelRequest tunnelRequest)
      throws IOException {
    if (connected) {
      throw new IllegalStateException("already connected");
    }
    connected = true;
    socket = (route.proxy.type() != Proxy.Type.HTTP) ? new Socket(route.proxy) : new Socket();
    Platform.get().connectSocket(socket, route.inetSocketAddress, connectTimeout);
    socket.setSoTimeout(readTimeout);
    in = socket.getInputStream();
    out = socket.getOutputStream();

    if (route.address.sslSocketFactory != null) {
      upgradeToTls(tunnelRequest);
    }

    // Use MTU-sized buffers to send fewer packets.
    int mtu = Platform.get().getMtu(socket);
    if (mtu < 1024) mtu = 1024;
    if (mtu > 8192) mtu = 8192;
    in = new BufferedInputStream(in, mtu);
    out = new BufferedOutputStream(out, mtu);
  }

  /**
   * Create an {@code SSLSocket} and perform the TLS handshake and certificate
   * validation.
   */
  private void upgradeToTls(TunnelRequest tunnelRequest) throws IOException {
    Platform platform = Platform.get();

    // Make an SSL Tunnel on the first message pair of each SSL + proxy connection.
    if (requiresTunnel()) {
      makeTunnel(tunnelRequest);
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

    List<String> transports = route.address.transports;
    boolean useNpn = route.modernTls
        && (transports.contains("spdy/3") || transports.contains("HTTP-draft-04/2.0"));
    if (useNpn) {
      platform.setNpnProtocols(sslSocket, NPN_PROTOCOLS);
    }

    // Force handshake. This can throw!
    sslSocket.startHandshake();

    // Verify that the socket's certificates are acceptable for the target host.
    if (!route.address.hostnameVerifier.verify(route.address.uriHost, sslSocket.getSession())) {
      throw new IOException("Hostname '" + route.address.uriHost + "' was not verified");
    }

    out = sslSocket.getOutputStream();
    in = sslSocket.getInputStream();

    byte[] selectedProtocol;
    if (useNpn && (selectedProtocol = platform.getNpnSelectedProtocol(sslSocket)) != null) {
      if (Arrays.equals(selectedProtocol, HTTP2_DRAFT_04)
          || Arrays.equals(selectedProtocol, SPDY3)) {
        sslSocket.setSoTimeout(0); // SPDY timeouts are set per-stream.
        SpdyConnection.Builder builder
            = new SpdyConnection.Builder(route.address.getUriHost(), true, in, out);
        if (Arrays.equals(selectedProtocol, HTTP2_DRAFT_04)) {
          builder.http20Draft04();
        } else {
          builder.spdy3();
        }
        spdyConnection = builder.build();
        spdyConnection.sendConnectionHeader();
      } else if (!Arrays.equals(selectedProtocol, HTTP_11)) {
        throw new IOException(
            "Unexpected NPN transport " + new String(selectedProtocol, "ISO-8859-1"));
      }
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
    if (!(in instanceof BufferedInputStream)) {
      return true; // Optimistic.
    }
    if (isSpdy()) {
      return true; // Optimistic. We can't test SPDY because its streams are in use.
    }
    BufferedInputStream bufferedInputStream = (BufferedInputStream) in;
    try {
      int readTimeout = socket.getSoTimeout();
      try {
        socket.setSoTimeout(1);
        bufferedInputStream.mark(1);
        if (bufferedInputStream.read() == -1) {
          return false; // Stream is exhausted; socket is closed.
        }
        bufferedInputStream.reset();
        return true;
      } finally {
        socket.setSoTimeout(readTimeout);
      }
    } catch (SocketTimeoutException ignored) {
      return true; // Read timed out; socket is good.
    } catch (IOException e) {
      return false; // Couldn't read; socket is closed.
    }
  }

  public void resetIdleStartTime() {
    if (spdyConnection != null) {
      throw new IllegalStateException("spdyConnection != null");
    }
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
    return isIdle() && System.nanoTime() - getIdleStartTimeNs() > keepAliveDurationNs;
  }

  /**
   * Returns the time in ns when this connection became idle. Undefined if
   * this connection is not idle.
   */
  public long getIdleStartTimeNs() {
    return spdyConnection == null ? idleStartTimeNs : spdyConnection.getIdleStartTimeNs();
  }

  /** Returns the transport appropriate for this connection. */
  public Object newTransport(HttpEngine httpEngine) throws IOException {
    return (spdyConnection != null)
        ? new SpdyTransport(httpEngine, spdyConnection)
        : new HttpTransport(httpEngine, out, in);
  }

  /**
   * Returns true if this is a SPDY connection. Such connections can be used
   * in multiple HTTP requests simultaneously.
   */
  public boolean isSpdy() {
    return spdyConnection != null;
  }

  public SpdyConnection getSpdyConnection() {
    return spdyConnection;
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

  /**
   * To make an HTTPS connection over an HTTP proxy, send an unencrypted
   * CONNECT request to create the proxy connection. This may need to be
   * retried if the proxy requires authorization.
   */
  private void makeTunnel(TunnelRequest tunnelRequest) throws IOException {
    RawHeaders requestHeaders = tunnelRequest.getRequestHeaders();
    while (true) {
      out.write(requestHeaders.toBytes());
      RawHeaders responseHeaders = RawHeaders.fromBytes(in);

      switch (responseHeaders.getResponseCode()) {
        case HTTP_OK:
          return;
        case HTTP_PROXY_AUTH:
          requestHeaders = new RawHeaders(requestHeaders);
          URL url = new URL("https", tunnelRequest.host, tunnelRequest.port, "/");
          boolean credentialsFound = HttpAuthenticator.processAuthHeader(
              route.address.authenticator, HTTP_PROXY_AUTH, responseHeaders, requestHeaders,
              route.proxy, url);
          if (credentialsFound) {
            continue;
          } else {
            throw new IOException("Failed to authenticate with proxy");
          }
        default:
          throw new IOException(
              "Unexpected response code for CONNECT: " + responseHeaders.getResponseCode());
      }
    }
  }
}
