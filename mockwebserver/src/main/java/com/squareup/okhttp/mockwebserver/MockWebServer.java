/*
 * Copyright (C) 2011 Google Inc.
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

package com.squareup.okhttp.mockwebserver;

import static com.squareup.okhttp.mockwebserver.SocketPolicy.DISCONNECT_AT_START;
import static com.squareup.okhttp.mockwebserver.SocketPolicy.FAIL_HANDSHAKE;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * A scriptable web server. Callers supply canned responses and the server
 * replays them upon request in sequence.
 */
public final class MockWebServer {

    static final String ASCII = "US-ASCII";

    private static final Logger logger = Logger.getLogger(MockWebServer.class.getName());
    private final BlockingQueue<RecordedRequest> requestQueue
            = new LinkedBlockingQueue<RecordedRequest>();
    /** All map values are Boolean.TRUE. (Collections.newSetFromMap isn't available in Froyo) */
    private final Map<Socket, Boolean> openClientSockets = new ConcurrentHashMap<Socket, Boolean>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private int bodyLimit = Integer.MAX_VALUE;
    private ServerSocket serverSocket;
    private SSLSocketFactory sslSocketFactory;
    private ExecutorService executor;
    private boolean tunnelProxy;
    private Dispatcher dispatcher = new QueueDispatcher();

    private int port = -1;

    public int getPort() {
        if (port == -1) {
            throw new IllegalStateException("Cannot retrieve port before calling play()");
        }
        return port;
    }

    public String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            throw new AssertionError(e);
        }
    }

    public Proxy toProxyAddress() {
        return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(getHostName(), getPort()));
    }

    /**
     * Returns a URL for connecting to this server.
     *
     * @param path the request path, such as "/".
     */
    public URL getUrl(String path) {
        try {
            return sslSocketFactory != null
                    ? new URL("https://" + getHostName() + ":" + getPort() + path)
                    : new URL("http://" + getHostName() + ":" + getPort() + path);
        } catch (MalformedURLException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Returns a cookie domain for this server. This returns the server's
     * non-loopback host name if it is known. Otherwise this returns ".local"
     * for this server's loopback name.
     */
    public String getCookieDomain() {
        String hostName = getHostName();
        return hostName.contains(".") ? hostName : ".local";
    }

    /**
     * Sets the number of bytes of the POST body to keep in memory to the given
     * limit.
     */
    public void setBodyLimit(int maxBodyLength) {
        this.bodyLimit = maxBodyLength;
    }

    /**
     * Serve requests with HTTPS rather than otherwise.
     *
     * @param tunnelProxy whether to expect the HTTP CONNECT method before
     *     negotiating TLS.
     */
    public void useHttps(SSLSocketFactory sslSocketFactory, boolean tunnelProxy) {
        this.sslSocketFactory = sslSocketFactory;
        this.tunnelProxy = tunnelProxy;
    }

    /**
     * Awaits the next HTTP request, removes it, and returns it. Callers should
     * use this to verify the request sent was as intended.
     */
    public RecordedRequest takeRequest() throws InterruptedException {
        return requestQueue.take();
    }

    /**
     * Returns the number of HTTP requests received thus far by this server.
     * This may exceed the number of HTTP connections when connection reuse is
     * in practice.
     */
    public int getRequestCount() {
        return requestCount.get();
    }

    /**
     * Scripts {@code response} to be returned to a request made in sequence.
     * The first request is served by the first enqueued response; the second
     * request by the second enqueued response; and so on.
     *
     * @throws ClassCastException if the default dispatcher has been replaced
     *     with {@link #setDispatcher(Dispatcher)}.
     */
    public void enqueue(MockResponse response) {
        ((QueueDispatcher) dispatcher).enqueueResponse(response.clone());
    }

    /**
     * Equivalent to {@code play(0)}.
     */
    public void play() throws IOException {
        play(0);
    }

    /**
     * Starts the server, serves all enqueued requests, and shuts the server
     * down.
     *
     * @param port the port to listen to, or 0 for any available port.
     *     Automated tests should always use port 0 to avoid flakiness when a
     *     specific port is unavailable.
     */
    public void play(int port) throws IOException {
        if (executor != null) {
            throw new IllegalStateException("play() already called");
        }
        executor = Executors.newCachedThreadPool();
        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);

        this.port = serverSocket.getLocalPort();
        executor.execute(namedRunnable("MockWebServer-accept-" + port, new Runnable() {
            public void run() {
                try {
                    acceptConnections();
                } catch (Throwable e) {
                    logger.log(Level.WARNING, "MockWebServer connection failed", e);
                }

                /*
                 * This gnarly block of code will release all sockets and
                 * all thread, even if any close fails.
                 */
                try {
                    serverSocket.close();
                } catch (Throwable e) {
                    logger.log(Level.WARNING, "MockWebServer server socket close failed", e);
                }
                for (Iterator<Socket> s = openClientSockets.keySet().iterator(); s.hasNext(); ) {
                    try {
                        s.next().close();
                        s.remove();
                    } catch (Throwable e) {
                        logger.log(Level.WARNING, "MockWebServer socket close failed", e);
                    }
                }
                try {
                    executor.shutdown();
                } catch (Throwable e) {
                    logger.log(Level.WARNING, "MockWebServer executor shutdown failed", e);
                }
            }

            private void acceptConnections() throws Exception {
                while (true) {
                    Socket socket;
                    try {
                        socket = serverSocket.accept();
                    } catch (SocketException e) {
                        return;
                    }
                    final SocketPolicy socketPolicy = dispatcher.peekSocketPolicy();
                    if (socketPolicy == DISCONNECT_AT_START) {
                        dispatchBookkeepingRequest(0, socket);
                        socket.close();
                    } else {
                        openClientSockets.put(socket, true);
                        serveConnection(socket);
                    }
                }
            }
        }));
    }

    public void shutdown() throws IOException {
        if (serverSocket != null) {
            serverSocket.close(); // should cause acceptConnections() to break out
        }
    }

    private void serveConnection(final Socket raw) {
        String name = "MockWebServer-" + raw.getRemoteSocketAddress();
        executor.execute(namedRunnable(name, new Runnable() {
            int sequenceNumber = 0;

            public void run() {
                try {
                    processConnection();
                } catch (Exception e) {
                    logger.log(Level.WARNING, "MockWebServer connection failed", e);
                }
            }

            public void processConnection() throws Exception {
                Socket socket;
                if (sslSocketFactory != null) {
                    if (tunnelProxy) {
                        createTunnel();
                    }
                    final SocketPolicy socketPolicy = dispatcher.peekSocketPolicy();
                    if (socketPolicy == FAIL_HANDSHAKE) {
                        dispatchBookkeepingRequest(sequenceNumber, raw);
                        processHandshakeFailure(raw, sequenceNumber++);
                        return;
                    }
                    socket = sslSocketFactory.createSocket(
                            raw, raw.getInetAddress().getHostAddress(), raw.getPort(), true);
                    ((SSLSocket) socket).setUseClientMode(false);
                    openClientSockets.put(socket, true);
                    openClientSockets.remove(raw);
                } else {
                    socket = raw;
                }

                InputStream in = new BufferedInputStream(socket.getInputStream());
                OutputStream out = new BufferedOutputStream(socket.getOutputStream());

                while (processOneRequest(socket, in, out)) {
                }

                if (sequenceNumber == 0) {
                    logger.warning("MockWebServer connection didn't make a request");
                }

                in.close();
                out.close();
                socket.close();
                openClientSockets.remove(socket);
            }

            /**
             * Respond to CONNECT requests until a SWITCH_TO_SSL_AT_END response
             * is dispatched.
             */
            private void createTunnel() throws IOException, InterruptedException {
                while (true) {
                    final SocketPolicy socketPolicy = dispatcher.peekSocketPolicy();
                    if (!processOneRequest(raw, raw.getInputStream(), raw.getOutputStream())) {
                        throw new IllegalStateException("Tunnel without any CONNECT!");
                    }
                    if (socketPolicy == SocketPolicy.UPGRADE_TO_SSL_AT_END) {
                        return;
                    }
                }
            }

            /**
             * Reads a request and writes its response. Returns true if a request
             * was processed.
             */
            private boolean processOneRequest(Socket socket, InputStream in, OutputStream out)
                    throws IOException, InterruptedException {
                RecordedRequest request = readRequest(socket, in, out, sequenceNumber);
                if (request == null) {
                    return false;
                }
                requestCount.incrementAndGet();
                requestQueue.add(request);
                MockResponse response = dispatcher.dispatch(request);
                writeResponse(out, response);
                if (response.getSocketPolicy() == SocketPolicy.DISCONNECT_AT_END) {
                    in.close();
                    out.close();
                } else if (response.getSocketPolicy() == SocketPolicy.SHUTDOWN_INPUT_AT_END) {
                    socket.shutdownInput();
                } else if (response.getSocketPolicy() == SocketPolicy.SHUTDOWN_OUTPUT_AT_END) {
                    socket.shutdownOutput();
                }
                logger.info("Received request: " + request + " and responded: " + response);
                sequenceNumber++;
                return true;
            }
        }));
    }

    private void processHandshakeFailure(Socket raw, int sequenceNumber) throws Exception {
        X509TrustManager untrusted = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                throw new CertificateException();
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {
                throw new AssertionError();
            }
            @Override public X509Certificate[] getAcceptedIssuers() {
                throw new AssertionError();
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] { untrusted }, new SecureRandom());
        SSLSocketFactory sslSocketFactory = context.getSocketFactory();
        SSLSocket socket = (SSLSocket) sslSocketFactory.createSocket(
                raw, raw.getInetAddress().getHostAddress(), raw.getPort(), true);
        try {
            socket.startHandshake(); // we're testing a handshake failure
            throw new AssertionError();
        } catch (IOException expected) {
        }
        socket.close();
    }

    private void dispatchBookkeepingRequest(int sequenceNumber, Socket socket)
            throws InterruptedException {
        requestCount.incrementAndGet();
        dispatcher.dispatch(
            new RecordedRequest(null, null, null, -1, null, sequenceNumber, socket));
    }

    /**
     * @param sequenceNumber the index of this request on this connection.
     */
    private RecordedRequest readRequest(Socket socket, InputStream in, OutputStream out,
            int sequenceNumber) throws IOException {
        String request;
        try {
            request = readAsciiUntilCrlf(in);
        } catch (IOException streamIsClosed) {
            return null; // no request because we closed the stream
        }
        if (request.length() == 0) {
            return null; // no request because the stream is exhausted
        }

        List<String> headers = new ArrayList<String>();
        long contentLength = -1;
        boolean chunked = false;
        boolean expectContinue = false;
        String header;
        while ((header = readAsciiUntilCrlf(in)).length() != 0) {
            headers.add(header);
            String lowercaseHeader = header.toLowerCase(Locale.US);
            if (contentLength == -1 && lowercaseHeader.startsWith("content-length:")) {
                contentLength = Long.parseLong(header.substring(15).trim());
            }
            if (lowercaseHeader.startsWith("transfer-encoding:")
                    && lowercaseHeader.substring(18).trim().equals("chunked")) {
                chunked = true;
            }
            if (lowercaseHeader.startsWith("expect:")
                    && lowercaseHeader.substring(7).trim().equals("100-continue")) {
                expectContinue = true;
            }
        }

        if (expectContinue) {
            out.write(("HTTP/1.1 100 Continue\r\n").getBytes(ASCII));
            out.write(("Content-Length: 0\r\n").getBytes(ASCII));
            out.write(("\r\n").getBytes(ASCII));
            out.flush();
        }

        boolean hasBody = false;
        TruncatingOutputStream requestBody = new TruncatingOutputStream();
        List<Integer> chunkSizes = new ArrayList<Integer>();
        if (contentLength != -1) {
            hasBody = true;
            transfer(contentLength, in, requestBody);
        } else if (chunked) {
            hasBody = true;
            while (true) {
                int chunkSize = Integer.parseInt(readAsciiUntilCrlf(in).trim(), 16);
                if (chunkSize == 0) {
                    readEmptyLine(in);
                    break;
                }
                chunkSizes.add(chunkSize);
                transfer(chunkSize, in, requestBody);
                readEmptyLine(in);
            }
        }

        if (request.startsWith("OPTIONS ") || request.startsWith("GET ")
                || request.startsWith("HEAD ") || request.startsWith("DELETE ")
                || request.startsWith("TRACE ") || request.startsWith("CONNECT ")) {
            if (hasBody) {
                throw new IllegalArgumentException("Request must not have a body: " + request);
            }
        } else if (!request.startsWith("POST ") && !request.startsWith("PUT ")) {
            throw new UnsupportedOperationException("Unexpected method: " + request);
        }

        return new RecordedRequest(request, headers, chunkSizes,
                requestBody.numBytesReceived, requestBody.toByteArray(), sequenceNumber, socket);
    }

    private void writeResponse(OutputStream out, MockResponse response) throws IOException {
        out.write((response.getStatus() + "\r\n").getBytes(ASCII));
        for (String header : response.getHeaders()) {
            out.write((header + "\r\n").getBytes(ASCII));
        }
        out.write(("\r\n").getBytes(ASCII));
        out.flush();

        final InputStream in = response.getBodyStream();
        if (in == null) {
            return;
        }
        final int bytesPerSecond = response.getBytesPerSecond();

        // Stream data in MTU-sized increments, with a minimum of one packet per second.
        final byte[] buffer = bytesPerSecond >= 1452
                ? new byte[1452]
                : new byte[bytesPerSecond];
        final long delayMs;
        if (bytesPerSecond == Integer.MAX_VALUE) {
            delayMs = 0;
        } else {
            delayMs = (1000 * buffer.length) / bytesPerSecond;
        }

        int read;
        long sinceDelay = 0;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            out.flush();

            sinceDelay += read;
            if (sinceDelay >= buffer.length && delayMs > 0) {
                sinceDelay %= buffer.length;
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    throw new AssertionError();
                }
            }
        }
    }

    /**
     * Transfer bytes from {@code in} to {@code out} until either {@code length}
     * bytes have been transferred or {@code in} is exhausted.
     */
    private void transfer(long length, InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        while (length > 0) {
            int count = in.read(buffer, 0, (int) Math.min(buffer.length, length));
            if (count == -1) {
                return;
            }
            out.write(buffer, 0, count);
            length -= count;
        }
    }

    /**
     * Returns the text from {@code in} until the next "\r\n", or null if
     * {@code in} is exhausted.
     */
    private String readAsciiUntilCrlf(InputStream in) throws IOException {
        StringBuilder builder = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c == '\n' && builder.length() > 0 && builder.charAt(builder.length() - 1) == '\r') {
                builder.deleteCharAt(builder.length() - 1);
                return builder.toString();
            } else if (c == -1) {
                return builder.toString();
            } else {
                builder.append((char) c);
            }
        }
    }

    private void readEmptyLine(InputStream in) throws IOException {
        String line = readAsciiUntilCrlf(in);
        if (line.length() != 0) {
            throw new IllegalStateException("Expected empty but was: " + line);
        }
    }

    /**
     * Sets the dispatcher used to match incoming requests to mock responses.
     * The default dispatcher simply serves a fixed sequence of responses from
     * a {@link #enqueue(MockResponse) queue}; custom dispatchers can vary the
     * response based on timing or the content of the request.
     */
    public void setDispatcher(Dispatcher dispatcher) {
        if (dispatcher == null) {
            throw new NullPointerException();
        }
        this.dispatcher = dispatcher;
    }

    /**
     * An output stream that drops data after bodyLimit bytes.
     */
    private class TruncatingOutputStream extends ByteArrayOutputStream {
        private long numBytesReceived = 0;
        @Override public void write(byte[] buffer, int offset, int len) {
            numBytesReceived += len;
            super.write(buffer, offset, Math.min(len, bodyLimit - count));
        }
        @Override public void write(int oneByte) {
            numBytesReceived++;
            if (count < bodyLimit) {
                super.write(oneByte);
            }
        }
    }

    private static Runnable namedRunnable(final String name, final Runnable runnable) {
        return new Runnable() {
            public void run() {
                String originalName = Thread.currentThread().getName();
                Thread.currentThread().setName(name);
                try {
                    runnable.run();
                } finally {
                    Thread.currentThread().setName(originalName);
                }
            }
        };
    }
}
