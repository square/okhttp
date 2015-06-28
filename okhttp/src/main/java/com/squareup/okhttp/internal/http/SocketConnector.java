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
import com.squareup.okhttp.CertificatePinner;
import com.squareup.okhttp.Connection;
import com.squareup.okhttp.ConnectionPool;
import com.squareup.okhttp.ConnectionSpec;
import com.squareup.okhttp.Handshake;
import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.Route;
import com.squareup.okhttp.internal.Platform;
import com.squareup.okhttp.internal.ConnectionSpecSelector;
import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.tls.OkHostnameVerifier;

import java.io.IOException;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import okio.Source;

import static com.squareup.okhttp.internal.Util.closeQuietly;
import static com.squareup.okhttp.internal.Util.getDefaultPort;
import static com.squareup.okhttp.internal.Util.getEffectivePort;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;

/**
 * Helper that can establish a socket connection to a {@link com.squareup.okhttp.Route} using the
 * specified {@link ConnectionSpec} set. A {@link SocketConnector} can be used multiple times.
 */
public class SocketConnector {
  private final Connection connection;
  private final ConnectionPool connectionPool;

  public SocketConnector(Connection connection, ConnectionPool connectionPool) {
    this.connection = connection;
    this.connectionPool = connectionPool;
  }

  public ConnectedSocket connectCleartext(int connectTimeout, int readTimeout, Route route)
      throws RouteException {
    Socket socket = connectRawSocket(readTimeout, connectTimeout, route);
    return new ConnectedSocket(route, socket);
  }

  public ConnectedSocket connectTls(int connectTimeout, int readTimeout,
      int writeTimeout, Request request, Route route, List<ConnectionSpec> connectionSpecs,
      boolean connectionRetryEnabled) throws RouteException {

    Address address = route.getAddress();
    ConnectionSpecSelector connectionSpecSelector = new ConnectionSpecSelector(connectionSpecs);
    RouteException routeException = null;
    do {
      Socket socket = connectRawSocket(readTimeout, connectTimeout, route);
      if (route.requiresTunnel()) {
        createTunnel(readTimeout, writeTimeout, request, route, socket);
      }

      SSLSocket sslSocket = null;
      try {
        SSLSocketFactory sslSocketFactory = address.getSslSocketFactory();

        // Create the wrapper over the connected socket.
        sslSocket = (SSLSocket) sslSocketFactory
            .createSocket(socket, address.getUriHost(), address.getUriPort(), true /* autoClose */);

        // Configure the socket's ciphers, TLS versions, and extensions.
        ConnectionSpec connectionSpec = connectionSpecSelector.configureSecureSocket(sslSocket);
        Platform platform = Platform.get();
        Handshake handshake = null;
        Protocol alpnProtocol = null;
        try {
          if (connectionSpec.supportsTlsExtensions()) {
            platform.configureTlsExtensions(
                sslSocket, address.getUriHost(), address.getProtocols());
          }
          // Force handshake. This can throw!
          sslSocket.startHandshake();

          handshake = Handshake.get(sslSocket.getSession());

          String maybeProtocol;
          if (connectionSpec.supportsTlsExtensions()
              && (maybeProtocol = platform.getSelectedProtocol(sslSocket)) != null) {
            alpnProtocol = Protocol.get(maybeProtocol); // Throws IOE on unknown.
          }
        } finally {
          platform.afterHandshake(sslSocket);
        }

        // Verify that the socket's certificates are acceptable for the target host.
        if (!address.getHostnameVerifier().verify(address.getUriHost(), sslSocket.getSession())) {
          X509Certificate cert = (X509Certificate) sslSocket.getSession()
              .getPeerCertificates()[0];
          throw new SSLPeerUnverifiedException(
              "Hostname " + address.getUriHost() + " not verified:"
              + "\n    certificate: " + CertificatePinner.pin(cert)
              + "\n    DN: " + cert.getSubjectDN().getName()
              + "\n    subjectAltNames: " + OkHostnameVerifier.allSubjectAltNames(cert));
        }

        // Check that the certificate pinner is satisfied by the certificates presented.
        address.getCertificatePinner().check(address.getUriHost(), handshake.peerCertificates());

        return new ConnectedSocket(route, sslSocket, alpnProtocol, handshake);
      } catch (IOException e) {
        boolean canRetry = connectionRetryEnabled && connectionSpecSelector.connectionFailed(e);
        closeQuietly(sslSocket);
        closeQuietly(socket);
        if (routeException == null) {
          routeException = new RouteException(e);
        } else {
          routeException.addConnectException(e);
        }
        if (!canRetry) {
          throw routeException;
        }
      }
    } while (true);
  }

  private Socket connectRawSocket(int soTimeout, int connectTimeout, Route route)
      throws RouteException {
    Platform platform = Platform.get();
    try {
      Proxy proxy = route.getProxy();
      Address address = route.getAddress();
      Socket socket;
      if (proxy.type() == Proxy.Type.DIRECT || proxy.type() == Proxy.Type.HTTP) {
        socket = address.getSocketFactory().createSocket();
      } else {
        socket = new Socket(proxy);
      }
      platform.connectSocket(socket, route.getSocketAddress(), connectTimeout);
      socket.setSoTimeout(soTimeout);

      return socket;
    } catch (IOException e) {
      throw new RouteException(e);
    }
  }

  /**
   * To make an HTTPS connection over an HTTP proxy, send an unencrypted
   * CONNECT request to create the proxy connection. This may need to be
   * retried if the proxy requires authorization.
   */
  private void createTunnel(int readTimeout, int writeTimeout, Request request, Route route,
      Socket socket) throws RouteException {
    // Make an SSL Tunnel on the first message pair of each SSL + proxy connection.
    try {
      Request tunnelRequest = createTunnelRequest(request);
      HttpConnection tunnelConnection = new HttpConnection(connectionPool, connection, socket);
      tunnelConnection.setTimeouts(readTimeout, writeTimeout);
      URL url = tunnelRequest.url();
      String requestLine = "CONNECT " + url.getHost() + ":" + getEffectivePort(url) + " HTTP/1.1";
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
    } catch (IOException e) {
      throw new RouteException(e);
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
    String host = request.url().getHost();
    int port = getEffectivePort(request.url());
    String authority = (port == getDefaultPort("https")) ? host : (host + ":" + port);
    Request.Builder result = new Request.Builder()
        .url(new URL("https", host, port, "/"))
        .header("Host", authority)
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
   * A connected socket with metadata.
   */
  public static class ConnectedSocket {
    public final Route route;
    public final Socket socket;
    public final Protocol alpnProtocol;
    public final Handshake handshake;

    /** A connected plain / raw (i.e. unencrypted communication) socket. */
    public ConnectedSocket(Route route, Socket socket) {
      this.route = route;
      this.socket = socket;
      alpnProtocol = null;
      handshake = null;
    }

    /** A connected {@link SSLSocket}. */
    public ConnectedSocket(Route route, SSLSocket socket, Protocol alpnProtocol,
        Handshake handshake) {
      this.route = route;
      this.socket = socket;
      this.alpnProtocol = alpnProtocol;
      this.handshake = handshake;
    }
  }
}
