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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
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

    private final Address address;
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private boolean recycled = false;
    private SpdyConnection spdyConnection;

    HttpConnection(Address address, Socket socket, InputStream in, OutputStream out) {
        this.address = address;
        this.socket = socket;
        this.in = in;
        this.out = out;
    }

    /**
     * The version this client will use. Either 0 for HTTP/1.0, or 1 for
     * HTTP/1.1. Upon receiving a non-HTTP/1.1 response, this client
     * automatically sets its version to HTTP/1.0.
     */
    int httpMinorVersion = 1; // Assume HTTP/1.1

    public static HttpConnection connect(URI uri, SSLSocketFactory sslSocketFactory,
            HostnameVerifier hostnameVerifier, Proxy proxy, int connectTimeout, int readTimeout,
            TunnelConfig tunnelConfig) throws IOException {
        HttpConnection result = getConnection(uri, sslSocketFactory, hostnameVerifier, proxy,
                connectTimeout, tunnelConfig);
        result.socket.setSoTimeout(readTimeout);
        return result;
    }

    /**
     * Selects a proxy and gets a connection with that proxy.
     */
    private static HttpConnection getConnection(URI uri, SSLSocketFactory sslSocketFactory,
            HostnameVerifier hostnameVerifier, Proxy proxy, int connectTimeout,
            TunnelConfig tunnelConfig) throws IOException {
        // Try an explicitly-specified proxy.
        if (proxy != null) {
            Address address = (proxy.type() == Proxy.Type.DIRECT)
                    ? new Address(uri, sslSocketFactory, hostnameVerifier)
                    : new Address(uri, sslSocketFactory, hostnameVerifier, proxy);
            return getConnectionToAddress(address, connectTimeout, tunnelConfig);
        }

        // Try each proxy provided by the ProxySelector until a connection succeeds.
        ProxySelector selector = ProxySelector.getDefault();
        List<Proxy> proxyList = selector.select(uri);
        if (proxyList != null) {
            for (Proxy selectedProxy : proxyList) {
                if (selectedProxy.type() == Proxy.Type.DIRECT) {
                    // the same as NO_PROXY
                    // TODO: if the selector recommends a direct connection, attempt that?
                    continue;
                }
                try {
                    return getConnectionToAddress(new Address(uri, sslSocketFactory,
                            hostnameVerifier, selectedProxy), connectTimeout, tunnelConfig);
                } catch (IOException e) {
                    // failed to connect, tell it to the selector
                    selector.connectFailed(uri, selectedProxy.address(), e);
                }
            }
        }

        // Try a direct connection. If this fails, this method will throw.
        return getConnectionToAddress(new Address(uri, sslSocketFactory, hostnameVerifier),
                connectTimeout, tunnelConfig);
    }

    /**
     * Selects a proxy and gets a connection with that proxy.
     */
    private static HttpConnection getConnectionToAddress(Address address, int connectTimeout,
            TunnelConfig tunnelConfig) throws IOException {
        HttpConnection pooled = HttpConnectionPool.INSTANCE.get(address);
        if (pooled != null) {
            return pooled;
        }

        Socket socket = connectSocket(address, connectTimeout);
        HttpConnection result = new HttpConnection(
                address, socket, socket.getInputStream(), socket.getOutputStream());

        if (address.sslSocketFactory != null) {
            // First try an SSL connection with compression and various TLS
            // extensions enabled, if it fails (and its not unheard of that it
            // will) fallback to a barebones connection.
            try {
                result = new HttpConnection(
                        address, socket, socket.getInputStream(), socket.getOutputStream());
                result.upgradeToTls(true, tunnelConfig);
            } catch (IOException e) {
                // If the problem was a CertificateException from the X509TrustManager,
                // do not retry, we didn't have an abrupt server initiated exception.
                if (e instanceof SSLHandshakeException
                        && e.getCause() instanceof CertificateException) {
                    throw e;
                }
                result.closeSocketAndStreams();

                socket = connectSocket(address, connectTimeout);
                result = new HttpConnection(
                        address, socket, socket.getInputStream(), socket.getOutputStream());
                result.upgradeToTls(false, tunnelConfig);
            }
        }

        /*
         * Buffer the socket stream to permit efficient parsing of HTTP headers
         * and chunk sizes. This also masks SSL InputStream's degenerate
         * available() implementation. That way we can read the end of a chunked
         * response without blocking and will recycle connections more reliably.
         * http://code.google.com/p/android/issues/detail?id=38817
         */
        int bufferSize = 128;
        result.in = new BufferedInputStream(result.in, bufferSize);

        return result;
    }

    /**
     * Try each of the host's addresses for best behavior in mixed IPv4/IPv6
     * environments. See http://b/2876927
     */
    private static Socket connectSocket(Address address, int connectTimeout) throws IOException {
        Socket socket = null;
        InetAddress[] addresses = InetAddress.getAllByName(address.socketHost);
        for (int i = 0; i < addresses.length; i++) {
            socket = (address.proxy != null && address.proxy.type() != Proxy.Type.HTTP)
                    ? new Socket(address.proxy)
                    : new Socket();
            try {
                socket.connect(
                        new InetSocketAddress(addresses[i], address.socketPort), connectTimeout);
                break;
            } catch (IOException e) {
                if (i == addresses.length - 1) {
                    throw e;
                }
            }
        }

        if (socket == null) {
            throw new IOException();
        }

        return socket;
    }

    /**
     * Create an {@code SSLSocket} and perform the TLS handshake and certificate
     * validation.
     *
     * @param tlsTolerant If true, assume server can handle common TLS
     *     extensions and SSL deflate compression. If false, use an SSL3 only
     *     fallback mode without compression.
     */
    private void upgradeToTls(boolean tlsTolerant, TunnelConfig tunnelConfig) throws IOException {
        // Make an SSL Tunnel on the first message pair of each SSL + proxy connection.
        if (address.requiresTunnel()) {
            makeTunnel(tunnelConfig);
        }

        // Create the wrapper over connected socket.
        socket = address.sslSocketFactory.createSocket(
                socket, address.uriHost, address.uriPort, true /* autoClose */);
        SSLSocket sslSocket = (SSLSocket) socket;
        Libcore.makeTlsTolerant(sslSocket, address.uriHost, tlsTolerant);

        if (tlsTolerant) {
            Libcore.setNpnProtocols(sslSocket, NPN_PROTOCOLS);
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
        if (tlsTolerant
                && (selectedProtocol = Libcore.getNpnSelectedProtocol(sslSocket)) != null) {
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

    public Socket getSocket() {
        return socket;
    }

    public Address getAddress() {
        return address;
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
                        responseHeaders, requestHeaders, address.proxy, tunnelConfig.url);
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
     * This address has two parts: the address we connect to directly and the
     * origin address of the resource. These are the same unless a proxy is
     * being used. It also includes the SSL socket factory so that a socket will
     * not be reused if its SSL configuration is different.
     */
    public static final class Address {
        private final Proxy proxy;
        private final String uriHost;
        private final int uriPort;
        private final String socketHost;
        private final int socketPort;
        private final SSLSocketFactory sslSocketFactory;
        private final HostnameVerifier hostnameVerifier;

        public Address(URI uri, SSLSocketFactory sslSocketFactory,
                HostnameVerifier hostnameVerifier) throws UnknownHostException {
            this.proxy = null;
            this.uriHost = uri.getHost();
            this.uriPort = Libcore.getEffectivePort(uri);
            this.sslSocketFactory = sslSocketFactory;
            this.hostnameVerifier = hostnameVerifier;
            this.socketHost = uriHost;
            this.socketPort = uriPort;
            if (uriHost == null) {
                throw new UnknownHostException(uri.toString());
            }
        }

        public Address(URI uri, SSLSocketFactory sslSocketFactory,
                HostnameVerifier hostnameVerifier, Proxy proxy) throws UnknownHostException {
            this.proxy = proxy;
            this.uriHost = uri.getHost();
            this.uriPort = Libcore.getEffectivePort(uri);
            this.sslSocketFactory = sslSocketFactory;
            this.hostnameVerifier = hostnameVerifier;

            SocketAddress proxyAddress = proxy.address();
            if (!(proxyAddress instanceof InetSocketAddress)) {
                throw new IllegalArgumentException("Proxy.address() is not an InetSocketAddress: "
                        + proxyAddress.getClass());
            }
            InetSocketAddress proxySocketAddress = (InetSocketAddress) proxyAddress;
            this.socketHost = proxySocketAddress.getHostName();
            this.socketPort = proxySocketAddress.getPort();
            if (uriHost == null) {
                throw new UnknownHostException(uri.toString());
            }
        }

        public Proxy getProxy() {
            return proxy;
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

        /**
         * Returns true if the HTTP connection needs to tunnel one protocol over
         * another, such as when using HTTPS through an HTTP proxy. When doing so,
         * we must avoid buffering bytes intended for the higher-level protocol.
         */
        public boolean requiresTunnel() {
            return sslSocketFactory != null && proxy != null && proxy.type() == Proxy.Type.HTTP;
        }
    }
}
