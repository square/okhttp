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

package libcore.net.http;

import static com.squareup.okhttp.OkHttpConnection.HTTP_PROXY_AUTH;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import static java.net.HttpURLConnection.HTTP_OK;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Arrays;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import libcore.Platform;
import libcore.io.IoUtils;
import libcore.net.spdy.SpdyConnection;
import libcore.util.Libcore;
import libcore.util.Objects;

/**
 * Holds the sockets and streams of an HTTP, HTTPS, or HTTPS+SPDY connection,
 * which may be used for multiple HTTP request/response exchanges. Connections
 * may be direct to the origin server or via a proxy. Create an instance using
 * the {@link Address} inner class.
 *
 * <p>Do not confuse this class with the misnamed {@code HttpURLConnection},
 * which isn't so much a connection as a single request/response pair.
 */
final class HttpConnection {
    private static final byte[] NPN_PROTOCOLS = new byte[] {
            6, 's', 'p', 'd', 'y', '/', '2',
            8, 'h', 't', 't', 'p', '/', '1', '.', '1',
    };
    private static final byte[] SPDY2 = new byte[] {
            's', 'p', 'd', 'y', '/', '2',
    };
    private static final byte[] HTTP_11 = new byte[] {
            'h', 't', 't', 'p', '/', '1', '.', '1',
    };

    /** First try a TLS connection with various extensions enabled. */
    public static final int TLS_MODE_AGGRESSIVE = 1;

    /**
     * If that TLS connection fails (and its not unheard of that it will)
     * fall back to a basic SSLv3 connection.
     */
    public static final int TLS_MODE_COMPATIBLE = 0;

    /**
     * Unknown TLS mode.
     */
    public static final int TLS_MODE_NULL = -1;

    final Address address;
    final Proxy proxy;
    final InetSocketAddress inetSocketAddress;
    final int tlsMode;

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private boolean recycled = false;
    private SpdyConnection spdyConnection;

    /**
     * The version this client will use. Either 0 for HTTP/1.0, or 1 for
     * HTTP/1.1. Upon receiving a non-HTTP/1.1 response, this client
     * automatically sets its version to HTTP/1.0.
     */
    int httpMinorVersion = 1; // Assume HTTP/1.1

    HttpConnection(Address address, Proxy proxy, InetSocketAddress inetSocketAddress, int tlsMode) {
        if (address == null || proxy == null || inetSocketAddress == null) {
            throw new IllegalArgumentException();
        }
        this.address = address;
        this.proxy = proxy;
        this.inetSocketAddress = inetSocketAddress;
        this.tlsMode = tlsMode;
    }

    public void connect(int connectTimeout, int readTimeout, TunnelConfig tunnelConfig)
            throws IOException {
        socket = (proxy.type() != Proxy.Type.HTTP)
                ? new Socket(proxy)
                : new Socket();
        socket.connect(inetSocketAddress, connectTimeout);
        socket.setSoTimeout(readTimeout);
        in = socket.getInputStream();
        out = socket.getOutputStream();

        if (address.sslSocketFactory != null) {
            upgradeToTls(tunnelConfig);
        }

        /*
         * Buffer the socket stream to permit efficient parsing of HTTP headers
         * and chunk sizes. This also masks SSL InputStream's degenerate
         * available() implementation. That way we can read the end of a chunked
         * response without blocking and will recycle connections more reliably.
         * http://code.google.com/p/android/issues/detail?id=38817
         */
        int bufferSize = 128;
        in = new BufferedInputStream(in, bufferSize);
    }

    /**
     * Create an {@code SSLSocket} and perform the TLS handshake and certificate
     * validation.
     */
    private void upgradeToTls(TunnelConfig tunnelConfig) throws IOException {
        Platform platform = Platform.get();

        // Make an SSL Tunnel on the first message pair of each SSL + proxy connection.
        if (requiresTunnel()) {
            makeTunnel(tunnelConfig);
        }

        // Create the wrapper over connected socket.
        socket = address.sslSocketFactory.createSocket(
                socket, address.uriHost, address.uriPort, true /* autoClose */);
        SSLSocket sslSocket = (SSLSocket) socket;
        platform.makeTlsTolerant(sslSocket, address.uriHost, tlsMode == TLS_MODE_AGGRESSIVE);

        if (tlsMode == TLS_MODE_AGGRESSIVE) {
            platform.setNpnProtocols(sslSocket, NPN_PROTOCOLS);
        }

        // Force handshake. This can throw!
        sslSocket.startHandshake();

        // Verify that the socket's certificates are acceptable for the target host.
        if (!address.hostnameVerifier.verify(address.uriHost, sslSocket.getSession())) {
            throw new IOException("Hostname '" + address.uriHost + "' was not verified");
        }

        out = sslSocket.getOutputStream();
        in = sslSocket.getInputStream();

        byte[] selectedProtocol;
        if (tlsMode == TLS_MODE_AGGRESSIVE
                && (selectedProtocol = platform.getNpnSelectedProtocol(sslSocket)) != null) {
            if (Arrays.equals(selectedProtocol, SPDY2)) {
                spdyConnection = new SpdyConnection.Builder(true, in, out).build();
                HttpConnectionPool.INSTANCE.share(this);
            } else if (!Arrays.equals(selectedProtocol, HTTP_11)) {
                throw new IOException("Unexpected NPN transport "
                        + new String(selectedProtocol, "ISO-8859-1"));
            }
        }
    }

    public void closeSocketAndStreams() {
        IoUtils.closeQuietly(out);
        IoUtils.closeQuietly(in);
        IoUtils.closeQuietly(socket);
    }

    /**
     * Returns the proxy that this connection is using.
     *
     * <strong>Warning:</strong> This may be different than the proxy returned
     * by {@link #getAddress}! That is the proxy that the user asked to be
     * connected to; this returns the proxy that they were actually connected
     * to. The two may disagree when a proxy selector selects a different proxy
     * for a connection.
     */
    public Proxy getProxy() {
        return proxy;
    }

    public Address getAddress() {
        return address;
    }

    public Socket getSocket() {
        return socket;
    }

    /**
     * Returns true if this connection has been used to satisfy an earlier
     * HTTP request/response pair.
     */
    public boolean isRecycled() {
        return recycled;
    }

    public void setRecycled() {
        this.recycled = true;
    }

    /**
     * Returns true if this connection is eligible to be reused for another
     * request/response pair.
     */
    protected boolean isEligibleForRecycling() {
        return !socket.isClosed()
                && !socket.isInputShutdown()
                && !socket.isOutputShutdown();
    }

    /**
     * Returns the transport appropriate for this connection.
     */
    public Transport newTransport(HttpEngine httpEngine) throws IOException {
        if (spdyConnection != null) {
            return new SpdyTransport(httpEngine, spdyConnection);
        } else {
            return new HttpTransport(httpEngine, out, in);
        }
    }

    /**
     * Returns true if this is a SPDY connection. Such connections can be used
     * in multiple HTTP requests simultaneously.
     */
    public boolean isSpdy() {
        return spdyConnection != null;
    }

    public static final class TunnelConfig {
        private final URL url;
        private final String host;
        private final String userAgent;
        private final String proxyAuthorization;

        public TunnelConfig(URL url, String host, String userAgent, String proxyAuthorization) {
            if (url == null || host == null || userAgent == null) throw new NullPointerException();
            this.url = url;
            this.host = host;
            this.userAgent = userAgent;
            this.proxyAuthorization = proxyAuthorization;
        }

        /**
         * If we're establishing an HTTPS tunnel with CONNECT (RFC 2817 5.2), send
         * only the minimum set of headers. This avoids sending potentially
         * sensitive data like HTTP cookies to the proxy unencrypted.
         */
        RawHeaders getRequestHeaders() {
            RawHeaders result = new RawHeaders();
            result.setRequestLine("CONNECT " + url.getHost() + ":"
                    + Libcore.getEffectivePort(url) + " HTTP/1.1");

            // Always set Host and User-Agent.
            result.set("Host", host);
            result.set("User-Agent", userAgent);

            // Copy over the Proxy-Authorization header if it exists.
            if (proxyAuthorization != null) {
                result.set("Proxy-Authorization", proxyAuthorization);
            }

            // Always set the Proxy-Connection to Keep-Alive for the benefit of
            // HTTP/1.0 proxies like Squid.
            result.set("Proxy-Connection", "Keep-Alive");
            return result;
        }
    }

    /**
     * Returns true if the HTTP connection needs to tunnel one protocol over
     * another, such as when using HTTPS through an HTTP proxy. When doing so,
     * we must avoid buffering bytes intended for the higher-level protocol.
     */
    public boolean requiresTunnel() {
        return address.sslSocketFactory != null && proxy != null && proxy.type() == Proxy.Type.HTTP;
    }

    /**
     * To make an HTTPS connection over an HTTP proxy, send an unencrypted
     * CONNECT request to create the proxy connection. This may need to be
     * retried if the proxy requires authorization.
     */
    private void makeTunnel(TunnelConfig tunnelConfig) throws IOException {
        RawHeaders requestHeaders = tunnelConfig.getRequestHeaders();
        while (true) {
            out.write(requestHeaders.toBytes());
            RawHeaders responseHeaders = RawHeaders.fromBytes(in);

            switch (responseHeaders.getResponseCode()) {
            case HTTP_OK:
                return;
            case HTTP_PROXY_AUTH:
                requestHeaders = new RawHeaders(requestHeaders);
                boolean credentialsFound = HttpAuthenticator.processAuthHeader(HTTP_PROXY_AUTH,
                        responseHeaders, requestHeaders, proxy, tunnelConfig.url);
                if (credentialsFound) {
                    continue;
                } else {
                    throw new IOException("Failed to authenticate with proxy");
                }
            default:
                throw new IOException("Unexpected response code for CONNECT: "
                        + responseHeaders.getResponseCode());
            }
        }
    }

    /**
     * This address defines how the user has requested to connect ot the origin
     * server. It includes the destination host, the user's specified proxy (if
     * any), and the TLS configuration (if any). Used as a map key for pooling.
     */
    public static final class Address {
        final Proxy proxy;
        final String uriHost;
        final int uriPort;
        final SSLSocketFactory sslSocketFactory;
        final HostnameVerifier hostnameVerifier;

        public Address(URI uri, SSLSocketFactory sslSocketFactory,
                HostnameVerifier hostnameVerifier, Proxy proxy) throws UnknownHostException {
            this.proxy = proxy;
            this.uriHost = uri.getHost();
            this.uriPort = Libcore.getEffectivePort(uri);
            this.sslSocketFactory = sslSocketFactory;
            this.hostnameVerifier = hostnameVerifier;

            if (uriHost == null) {
                throw new UnknownHostException(uri.toString());
            }
        }

        @Override public boolean equals(Object other) {
            if (other instanceof Address) {
                Address that = (Address) other;
                return Objects.equal(this.proxy, that.proxy)
                        && this.uriHost.equals(that.uriHost)
                        && this.uriPort == that.uriPort
                        && Objects.equal(this.sslSocketFactory, that.sslSocketFactory)
                        && Objects.equal(this.hostnameVerifier, that.hostnameVerifier);
            }
            return false;
        }

        @Override public int hashCode() {
            int result = 17;
            result = 31 * result + uriHost.hashCode();
            result = 31 * result + uriPort;
            result = 31 * result + (sslSocketFactory != null ? sslSocketFactory.hashCode() : 0);
            result = 31 * result + (hostnameVerifier != null ? hostnameVerifier.hashCode() : 0);
            result = 31 * result + (proxy != null ? proxy.hashCode() : 0);
            return result;
        }
    }
}
