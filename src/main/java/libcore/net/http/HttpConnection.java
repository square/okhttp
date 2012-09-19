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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.HostnameVerifier;
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
    private final Socket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private SSLSocket sslSocket;
    private InputStream sslInputStream;
    private OutputStream sslOutputStream;
    private boolean recycled = false;
    private SpdyConnection spdyConnection;

    /**
     * The version this client will use. Either 0 for HTTP/1.0, or 1 for
     * HTTP/1.1. Upon receiving a non-HTTP/1.1 response, this client
     * automatically sets its version to HTTP/1.0.
     */
    int httpMinorVersion = 1; // Assume HTTP/1.1

    private HttpConnection(Address config, int connectTimeout) throws IOException {
        this.address = config;

        /*
         * Try each of the host's addresses for best behavior in mixed IPv4/IPv6
         * environments. See http://b/2876927
         * TODO: add a hidden method so that Socket.tryAllAddresses can does this for us
         */
        Socket socketCandidate = null;
        InetAddress[] addresses = InetAddress.getAllByName(config.socketHost);
        for (int i = 0; i < addresses.length; i++) {
            socketCandidate = (config.proxy != null && config.proxy.type() != Proxy.Type.HTTP)
                    ? new Socket(config.proxy)
                    : new Socket();
            try {
                socketCandidate.connect(
                        new InetSocketAddress(addresses[i], config.socketPort), connectTimeout);
                break;
            } catch (IOException e) {
                if (i == addresses.length - 1) {
                    throw e;
                }
            }
        }

        if (socketCandidate == null) {
            throw new IOException();
        }

        this.socket = socketCandidate;

        /*
         * Buffer the socket stream to permit efficient parsing of HTTP headers
         * and chunk sizes. Benchmarks suggest 128 is sufficient. We cannot
         * buffer when setting up a tunnel because we may consume bytes intended
         * for the SSL socket.
         */
        int bufferSize = 128;
        inputStream = address.requiresTunnel
                ? socket.getInputStream()
                : new BufferedInputStream(socket.getInputStream(), bufferSize);
        outputStream = socket.getOutputStream();
    }

    public static HttpConnection connect(URI uri, SSLSocketFactory sslSocketFactory,
            Proxy proxy, boolean requiresTunnel, int connectTimeout) throws IOException {
        /*
         * Try an explicitly-specified proxy.
         */
        if (proxy != null) {
            Address address = (proxy.type() == Proxy.Type.DIRECT)
                    ? new Address(uri, sslSocketFactory)
                    : new Address(uri, sslSocketFactory, proxy, requiresTunnel);
            return HttpConnectionPool.INSTANCE.get(address, connectTimeout);
        }

        /*
         * Try connecting to each of the proxies provided by the ProxySelector
         * until a connection succeeds.
         */
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
                    Address address = new Address(uri, sslSocketFactory,
                            selectedProxy, requiresTunnel);
                    return HttpConnectionPool.INSTANCE.get(address, connectTimeout);
                } catch (IOException e) {
                    // failed to connect, tell it to the selector
                    selector.connectFailed(uri, selectedProxy.address(), e);
                }
            }
        }

        /*
         * Try a direct connection. If this fails, this method will throw.
         */
        return HttpConnectionPool.INSTANCE.get(new Address(uri, sslSocketFactory), connectTimeout);
    }

    public void closeSocketAndStreams() {
        IoUtils.closeQuietly(sslOutputStream);
        IoUtils.closeQuietly(sslInputStream);
        IoUtils.closeQuietly(sslSocket);
        IoUtils.closeQuietly(outputStream);
        IoUtils.closeQuietly(inputStream);
        IoUtils.closeQuietly(socket);
    }

    public void setSoTimeout(int readTimeout) throws SocketException {
        socket.setSoTimeout(readTimeout);
    }

    Socket getSocket() {
        return sslSocket != null ? sslSocket : socket;
    }

    public Address getAddress() {
        return address;
    }

    /**
     * Create an {@code SSLSocket} and perform the SSL handshake
     * (performing certificate validation.
     *
     * @param sslSocketFactory Source of new {@code SSLSocket} instances.
     * @param tlsTolerant If true, assume server can handle common
     */
    public SSLSocket setupSecureSocket(SSLSocketFactory sslSocketFactory,
            HostnameVerifier hostnameVerifier, boolean tlsTolerant) throws IOException {
        if (spdyConnection != null || sslOutputStream != null || sslInputStream != null) {
            throw new IllegalStateException();
        }

        // Create the wrapper over connected socket.
        sslSocket = (SSLSocket) sslSocketFactory.createSocket(socket,
                address.uriHost, address.uriPort, true /* autoClose */);
        Libcore.makeTlsTolerant(sslSocket, address.uriHost, tlsTolerant);

        if (tlsTolerant) {
            Libcore.setNpnProtocols(sslSocket, NPN_PROTOCOLS);
        }

        // Force handshake. This can throw!
        sslSocket.startHandshake();

        // Verify that the socket's certificates are acceptable for the target host.
        if (!hostnameVerifier.verify(address.uriHost, sslSocket.getSession())) {
            throw new IOException("Hostname '" + address.uriHost + "' was not verified");
        }

        // SSL success. Prepare to hand out Transport instances.
        sslOutputStream = sslSocket.getOutputStream();
        sslInputStream = sslSocket.getInputStream();

        byte[] selectedProtocol;
        if (tlsTolerant
                && (selectedProtocol = Libcore.getNpnSelectedProtocol(sslSocket)) != null) {
            if (Arrays.equals(selectedProtocol, SPDY2)) {
                spdyConnection = new SpdyConnection.Builder(
                        true, sslInputStream, sslOutputStream).build();
                HttpConnectionPool.INSTANCE.share(this);
            } else if (!Arrays.equals(selectedProtocol, HTTP_11)) {
                throw new IOException("Unexpected NPN transport "
                        + new String(selectedProtocol, "ISO-8859-1"));
            }
        }

        return sslSocket;
    }

    /**
     * Return an {@code SSLSocket} if already connected, otherwise null.
     */
    public SSLSocket getSecureSocketIfConnected() {
        return sslSocket;
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
        } else if (sslSocket != null) {
            return new HttpTransport(httpEngine, sslOutputStream, sslInputStream);
        } else {
            return new HttpTransport(httpEngine, outputStream, inputStream);
        }
    }

    /**
     * Returns true if this is a SPDY connection. Such connections can be used
     * in multiple HTTP requests simultaneously.
     */
    public boolean isSpdy() {
        return spdyConnection != null;
    }

    /**
     * This address has two parts: the address we connect to directly and the
     * origin address of the resource. These are the same unless a proxy is
     * being used. It also includes the SSL socket factory so that a socket will
     * not be reused if its SSL configuration is different.
     */
    public static final class Address {
        private final Proxy proxy;
        private final boolean requiresTunnel;
        private final String uriHost;
        private final int uriPort;
        private final String socketHost;
        private final int socketPort;
        private final SSLSocketFactory sslSocketFactory;

        public Address(URI uri, SSLSocketFactory sslSocketFactory) throws UnknownHostException {
            this.proxy = null;
            this.requiresTunnel = false;
            this.uriHost = uri.getHost();
            this.uriPort = Libcore.getEffectivePort(uri);
            this.sslSocketFactory = sslSocketFactory;
            this.socketHost = uriHost;
            this.socketPort = uriPort;
            if (uriHost == null) {
                throw new UnknownHostException(uri.toString());
            }
        }

        /**
         * @param requiresTunnel true if the HTTP connection needs to tunnel one
         *     protocol over another, such as when using HTTPS through an HTTP
         *     proxy. When doing so, we must avoid buffering bytes intended for
         *     the higher-level protocol.
         */
        public Address(URI uri, SSLSocketFactory sslSocketFactory,
                Proxy proxy, boolean requiresTunnel) throws UnknownHostException {
            this.proxy = proxy;
            this.requiresTunnel = requiresTunnel;
            this.uriHost = uri.getHost();
            this.uriPort = Libcore.getEffectivePort(uri);
            this.sslSocketFactory = sslSocketFactory;

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
                        && this.requiresTunnel == that.requiresTunnel;
            }
            return false;
        }

        @Override public int hashCode() {
            int result = 17;
            result = 31 * result + uriHost.hashCode();
            result = 31 * result + uriPort;
            result = 31 * result + (sslSocketFactory != null ? sslSocketFactory.hashCode() : 0);
            result = 31 * result + (proxy != null ? proxy.hashCode() : 0);
            result = 31 * result + (requiresTunnel ? 1 : 0);
            return result;
        }

        public HttpConnection connect(int connectTimeout) throws IOException {
            return new HttpConnection(this, connectTimeout);
        }
    }
}
