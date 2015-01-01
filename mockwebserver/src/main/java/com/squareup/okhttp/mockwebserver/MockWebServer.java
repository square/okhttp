/*
 * Copyright (C) 2011 Google Inc.
 * Copyright (C) 2013 Square, Inc.
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

import com.squareup.okhttp.Headers;
import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.internal.NamedRunnable;
import com.squareup.okhttp.internal.Platform;
import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.spdy.ErrorCode;
import com.squareup.okhttp.internal.spdy.Header;
import com.squareup.okhttp.internal.spdy.IncomingStreamHandler;
import com.squareup.okhttp.internal.spdy.SpdyConnection;
import com.squareup.okhttp.internal.spdy.SpdyStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ByteString;
import okio.ForwardingSink;
import okio.Okio;
import okio.Sink;
import okio.Source;

import static com.squareup.okhttp.mockwebserver.SocketPolicy.DISCONNECT_AT_START;
import static com.squareup.okhttp.mockwebserver.SocketPolicy.FAIL_HANDSHAKE;

/**
 * A scriptable web server. Callers supply canned responses and the server
 * replays them upon request in sequence.
 */
public final class MockWebServer {
  private static final X509TrustManager UNTRUSTED_TRUST_MANAGER = new X509TrustManager() {
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

  private static final Logger logger = Logger.getLogger(MockWebServer.class.getName());

  private final BlockingQueue<RecordedRequest> requestQueue = new LinkedBlockingQueue<>();

  private final Set<Socket> openClientSockets =
      Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
  private final Set<SpdyConnection> openSpdyConnections =
      Collections.newSetFromMap(new ConcurrentHashMap<SpdyConnection, Boolean>());
  private final AtomicInteger requestCount = new AtomicInteger();
  private long bodyLimit = Long.MAX_VALUE;
  private ServerSocketFactory serverSocketFactory = ServerSocketFactory.getDefault();
  private ServerSocket serverSocket;
  private SSLSocketFactory sslSocketFactory;
  private ExecutorService executor;
  private boolean tunnelProxy;
  private Dispatcher dispatcher = new QueueDispatcher();

  private int port = -1;
  private InetAddress inetAddress;
  private boolean protocolNegotiationEnabled = true;
  private List<Protocol> protocols
      = Util.immutableList(Protocol.HTTP_2, Protocol.SPDY_3, Protocol.HTTP_1_1);

  public void setServerSocketFactory(ServerSocketFactory serverSocketFactory) {
    if (serverSocketFactory == null) throw new IllegalArgumentException("null serverSocketFactory");
    this.serverSocketFactory = serverSocketFactory;
  }

  public int getPort() {
    if (port == -1) throw new IllegalStateException("Call play() before getPort()");
    return port;
  }

  public String getHostName() {
    if (inetAddress == null) throw new IllegalStateException("Call play() before getHostName()");
    return inetAddress.getHostName();
  }

  public Proxy toProxyAddress() {
    if (inetAddress == null) throw new IllegalStateException("Call play() before toProxyAddress()");
    return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(inetAddress, getPort()));
  }

  /**
   * Returns a URL for connecting to this server.
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
   * non-loopback host name if it is known. Otherwise this returns ".local" for
   * this server's loopback name.
   */
  public String getCookieDomain() {
    String hostName = getHostName();
    return hostName.contains(".") ? hostName : ".local";
  }

  /** Sets the number of bytes of the request body to keep in memory to the given limit. */
  public void setBodyLimit(long byteCount) {
    this.bodyLimit = byteCount;
  }

  /**
   * Sets whether ALPN is used on incoming HTTPS connections to
   * negotiate a protocol like HTTP/1.1 or HTTP/2. Call this method to disable
   * negotiation and restrict connections to HTTP/1.1.
   */
  public void setProtocolNegotiationEnabled(boolean protocolNegotiationEnabled) {
    this.protocolNegotiationEnabled = protocolNegotiationEnabled;
  }

  /**
   * Indicates the protocols supported by ALPN on incoming HTTPS
   * connections. This list is ignored when
   * {@link #setProtocolNegotiationEnabled negotiation is disabled}.
   *
   * @param protocols the protocols to use, in order of preference. The list
   *     must contain {@linkplain Protocol#HTTP_1_1}. It must not contain null.
   */
  public void setProtocols(List<Protocol> protocols) {
    protocols = Util.immutableList(protocols);
    if (!protocols.contains(Protocol.HTTP_1_1)) {
      throw new IllegalArgumentException("protocols doesn't contain http/1.1: " + protocols);
    }
    if (protocols.contains(null)) {
      throw new IllegalArgumentException("protocols must not contain null");
    }
    this.protocols = protocols;
  }

  /**
   * Serve requests with HTTPS rather than otherwise.
   * @param tunnelProxy true to expect the HTTP CONNECT method before
   *     negotiating TLS.
   */
  public void useHttps(SSLSocketFactory sslSocketFactory, boolean tunnelProxy) {
    this.sslSocketFactory = sslSocketFactory;
    this.tunnelProxy = tunnelProxy;
  }

  /**
   * Awaits the next HTTP request, removes it, and returns it. Callers should
   * use this to verify the request was sent as intended. This method will block until the
   * request is available, possibly forever.
   *
   * @return the head of the request queue
   */
  public RecordedRequest takeRequest() throws InterruptedException {
    return requestQueue.take();
  }

  /**
   * Awaits the next HTTP request (waiting up to the
   * specified wait time if necessary), removes it, and returns it. Callers should
   * use this to verify the request was sent as intended within the given time.
   *
   * @param timeout how long to wait before giving up, in units of
  *        {@code unit}
   * @param unit a {@code TimeUnit} determining how to interpret the
   *        {@code timeout} parameter
   * @return the head of the request queue
   */
  public RecordedRequest takeRequest(int timeout, TimeUnit unit) throws InterruptedException {
    return requestQueue.poll(timeout, unit);
  }

  /**
   * Returns the number of HTTP requests received thus far by this server. This
   * may exceed the number of HTTP connections when connection reuse is in
   * practice.
   */
  public int getRequestCount() {
    return requestCount.get();
  }

  /**
   * Scripts {@code response} to be returned to a request made in sequence. The
   * first request is served by the first enqueued response; the second request
   * by the second enqueued response; and so on.
   *
   * @throws ClassCastException if the default dispatcher has been replaced
   *     with {@link #setDispatcher(Dispatcher)}.
   */
  public void enqueue(MockResponse response) {
    ((QueueDispatcher) dispatcher).enqueueResponse(response.clone());
  }

  /** Equivalent to {@code play(0)}. */
  public void play() throws IOException {
    play(0);
  }

  /**
   * Starts the server, serves all enqueued requests, and shuts the server down.
   *
   * @param port the port to listen to, or 0 for any available port. Automated
   *     tests should always use port 0 to avoid flakiness when a specific port
   *     is unavailable.
   */
  public void play(int port) throws IOException {
    if (executor != null) throw new IllegalStateException("play() already called");
    executor = Executors.newCachedThreadPool(Util.threadFactory("MockWebServer", false));
    inetAddress = InetAddress.getByName(null);
    serverSocket = serverSocketFactory.createServerSocket();
    serverSocket.setReuseAddress(port != 0); // Reuse the port if the port number was specified.
    serverSocket.bind(new InetSocketAddress(inetAddress, port), 50);

    this.port = serverSocket.getLocalPort();
    executor.execute(new NamedRunnable("MockWebServer %s", this.port) {
      @Override protected void execute() {
        try {
          logger.info(MockWebServer.this + " starting to accept connections");
          acceptConnections();
        } catch (Throwable e) {
          logger.log(Level.WARNING, MockWebServer.this + " failed unexpectedly", e);
        }

        // Release all sockets and all threads, even if any close fails.
        Util.closeQuietly(serverSocket);
        for (Iterator<Socket> s = openClientSockets.iterator(); s.hasNext(); ) {
          Util.closeQuietly(s.next());
          s.remove();
        }
        for (Iterator<SpdyConnection> s = openSpdyConnections.iterator(); s.hasNext(); ) {
          Util.closeQuietly(s.next());
          s.remove();
        }
        executor.shutdown();
      }

      private void acceptConnections() throws Exception {
        while (true) {
          Socket socket;
          try {
            socket = serverSocket.accept();
          } catch (SocketException e) {
            logger.info(MockWebServer.this + " done accepting connections: " + e.getMessage());
            return;
          }
          SocketPolicy socketPolicy = dispatcher.peek().getSocketPolicy();
          if (socketPolicy == DISCONNECT_AT_START) {
            dispatchBookkeepingRequest(0, socket);
            socket.close();
          } else {
            openClientSockets.add(socket);
            serveConnection(socket);
          }
        }
      }
    });
  }

  public void shutdown() throws IOException {
    // Cause acceptConnections() to break out.
    serverSocket.close();

    // Await shutdown.
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        throw new IOException("Gave up waiting for executor to shut down");
      }
    } catch (InterruptedException e) {
      throw new AssertionError();
    }
  }

  private void serveConnection(final Socket raw) {
    executor.execute(new NamedRunnable("MockWebServer %s", raw.getRemoteSocketAddress()) {
      int sequenceNumber = 0;

      @Override protected void execute() {
        try {
          processConnection();
        } catch (IOException e) {
          logger.info(MockWebServer.this + " connection from "
              + raw.getInetAddress() + " failed: " + e);
        } catch (Exception e) {
          logger.log(Level.SEVERE, MockWebServer.this + " connection from "
              + raw.getInetAddress() + " crashed", e);
        }
      }

      public void processConnection() throws Exception {
        Protocol protocol = Protocol.HTTP_1_1;
        Socket socket;
        if (sslSocketFactory != null) {
          if (tunnelProxy) {
            createTunnel();
          }
          SocketPolicy socketPolicy = dispatcher.peek().getSocketPolicy();
          if (socketPolicy == FAIL_HANDSHAKE) {
            dispatchBookkeepingRequest(sequenceNumber, raw);
            processHandshakeFailure(raw);
            return;
          }
          socket = sslSocketFactory.createSocket(raw, raw.getInetAddress().getHostAddress(),
              raw.getPort(), true);
          SSLSocket sslSocket = (SSLSocket) socket;
          sslSocket.setUseClientMode(false);
          openClientSockets.add(socket);

          if (protocolNegotiationEnabled) {
            Platform.get().configureTlsExtensions(sslSocket, null, protocols);
          }

          sslSocket.startHandshake();

          if (protocolNegotiationEnabled) {
            String protocolString = Platform.get().getSelectedProtocol(sslSocket);
            protocol = protocolString != null
                ? Protocol.get(protocolString)
                : Protocol.HTTP_1_1;
          }
          openClientSockets.remove(raw);
        } else {
          socket = raw;
        }

        if (protocol != Protocol.HTTP_1_1) {
          SpdySocketHandler spdySocketHandler = new SpdySocketHandler(socket, protocol);
          SpdyConnection spdyConnection = new SpdyConnection.Builder(false, socket)
              .protocol(protocol)
              .handler(spdySocketHandler).build();
          openSpdyConnections.add(spdyConnection);
          openClientSockets.remove(socket);
          return;
        }

        BufferedSource source = Okio.buffer(Okio.source(socket));
        BufferedSink sink = Okio.buffer(Okio.sink(socket));

        while (processOneRequest(socket, source, sink)) {
        }

        if (sequenceNumber == 0) {
          logger.warning(MockWebServer.this + " connection from " + raw.getInetAddress()
              + " didn't make a request");
        }

        source.close();
        sink.close();
        socket.close();
        openClientSockets.remove(socket);
      }

      /** Respond to CONNECT requests until a SWITCH_TO_SSL_AT_END response is dispatched. */
      private void createTunnel() throws IOException, InterruptedException {
        while (true) {
          SocketPolicy socketPolicy = dispatcher.peek().getSocketPolicy();
          BufferedSource source = Okio.buffer(Okio.source(raw));
          BufferedSink sink = Okio.buffer(Okio.sink(raw));
          if (!processOneRequest(raw, source, sink)) {
            throw new IllegalStateException("Tunnel without any CONNECT!");
          }
          if (socketPolicy == SocketPolicy.UPGRADE_TO_SSL_AT_END) return;
        }
      }

      /** Reads a request and writes its response. Returns true if a request was processed. */
      private boolean processOneRequest(Socket socket, BufferedSource source, BufferedSink sink)
          throws IOException, InterruptedException {
        RecordedRequest request = readRequest(socket, source, sink, sequenceNumber);
        if (request == null) return false;
        requestCount.incrementAndGet();
        requestQueue.add(request);
        MockResponse response = dispatcher.dispatch(request);
        if (response.getSocketPolicy() == SocketPolicy.DISCONNECT_AFTER_REQUEST) {
          socket.close();
          return false;
        }
        if (response.getSocketPolicy() == SocketPolicy.NO_RESPONSE) {
          // This read should block until the socket is closed. (Because nobody is writing.)
          if (source.read(new Buffer(), 1) == -1) return false;
          throw new ProtocolException("unexpected data");
        }
        writeResponse(sink, response);
        if (response.getSocketPolicy() == SocketPolicy.DISCONNECT_AT_END) {
          source.close();
          sink.close();
        } else if (response.getSocketPolicy() == SocketPolicy.SHUTDOWN_INPUT_AT_END) {
          socket.shutdownInput();
        } else if (response.getSocketPolicy() == SocketPolicy.SHUTDOWN_OUTPUT_AT_END) {
          socket.shutdownOutput();
        }
        if (logger.isLoggable(Level.INFO)) {
          logger.info(MockWebServer.this + " received request: " + request
              + " and responded: " + response);
        }
        sequenceNumber++;
        return true;
      }
    });
  }

  private void processHandshakeFailure(Socket raw) throws Exception {
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, new TrustManager[] { UNTRUSTED_TRUST_MANAGER }, new SecureRandom());
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
    dispatcher.dispatch(new RecordedRequest(null, null, null, -1, null, sequenceNumber, socket));
  }

  /** @param sequenceNumber the index of this request on this connection. */
  private RecordedRequest readRequest(Socket socket, BufferedSource source, BufferedSink sink,
      int sequenceNumber) throws IOException {
    String request;
    try {
      request = source.readUtf8LineStrict();
    } catch (IOException streamIsClosed) {
      return null; // no request because we closed the stream
    }
    if (request.length() == 0) {
      return null; // no request because the stream is exhausted
    }

    Headers.Builder headers = new Headers.Builder();
    long contentLength = -1;
    boolean chunked = false;
    boolean expectContinue = false;
    String header;
    while ((header = source.readUtf8LineStrict()).length() != 0) {
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
      sink.writeUtf8("HTTP/1.1 100 Continue\r\n");
      sink.writeUtf8("Content-Length: 0\r\n");
      sink.writeUtf8("\r\n");
      sink.flush();
    }

    MockResponse throttlePolicy = dispatcher.peek();

    boolean hasBody = false;
    Buffer buffer = new Buffer();
    TruncatingSink requestBody = new TruncatingSink(buffer, bodyLimit);
    List<Integer> chunkSizes = new ArrayList<>();
    if (contentLength != -1) {
      if (contentLength > 0) {
        hasBody = true;
        BufferedSink throttledSink = Okio.buffer(new ThrottledSink(requestBody, throttlePolicy));
        throttledSink.write(source, contentLength);
        throttledSink.flush();
      }
    } else if (chunked) {
      hasBody = true;
      while (true) {
        int chunkSize = Integer.parseInt(source.readUtf8LineStrict().trim(), 16);
        if (chunkSize == 0) {
          readEmptyLine(source);
          break;
        }
        chunkSizes.add(chunkSize);

        if (chunkSize > 0) {
          BufferedSink throttledSink = Okio.buffer(new ThrottledSink(requestBody, throttlePolicy));
          throttledSink.write(source, chunkSize);
          throttledSink.flush();
        }

        readEmptyLine(source);
      }
    }

    if (request.startsWith("OPTIONS ")
        || request.startsWith("GET ")
        || request.startsWith("HEAD ")
        || request.startsWith("TRACE ")
        || request.startsWith("CONNECT ")) {
      if (hasBody) {
        throw new IllegalArgumentException("Request must not have a body: " + request);
      }
    } else if (!request.startsWith("POST ")
        && !request.startsWith("PUT ")
        && !request.startsWith("PATCH ")
        && !request.startsWith("DELETE ")) { // Permitted as spec is ambiguous.
      throw new UnsupportedOperationException("Unexpected method: " + request);
    }

    return new RecordedRequest(request, headers.build(), chunkSizes, requestBody.bytesReceived,
        buffer, sequenceNumber, socket);
  }

  private void writeResponse(BufferedSink sink, MockResponse response) throws IOException {
    sink.writeUtf8(response.getStatus()).writeUtf8("\r\n");
    List<String> headers = response.getHeaders();
    for (int i = 0, size = headers.size(); i < size; i++) {
      String header = headers.get(i);
      sink.writeUtf8(header).writeUtf8("\r\n");
    }
    sink.writeUtf8("\r\n").flush();

    Source source = response.getBody();
    if (source == null) return;
    sleepIfDelayed(response);

    BufferedSink throttledSink = Okio.buffer(new ThrottledSink(sink, response));
    throttledSink.writeAll(source);
    throttledSink.flush();
  }

  private void sleepIfDelayed(MockResponse response) {
    if (response.getBodyDelayTimeMs() != 0) {
      try {
        Thread.sleep(response.getBodyDelayTimeMs());
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
    }
  }

  private static class ThrottledSink extends ForwardingSink {
    private final long bytesPerPeriod;
    private final long delayMs;
    private long periodRemainingBytes;

    public ThrottledSink(Sink delegate, MockResponse throttlePolicy) {
      super(delegate);
      bytesPerPeriod = throttlePolicy.getThrottleBytesPerPeriod();
      delayMs = throttlePolicy.getThrottlePeriod();
      periodRemainingBytes = bytesPerPeriod;
    }

    @Override public void write(Buffer source, long byteCount) throws IOException {
      while (byteCount > 0) {
        long actualCount = Math.min(Math.min(bytesPerPeriod, byteCount), periodRemainingBytes);
        super.write(source, actualCount);

        byteCount -= actualCount;
        periodRemainingBytes -= actualCount;

        if (periodRemainingBytes == 0 && byteCount > 0) {
          periodRemainingBytes = bytesPerPeriod;
          try {
            Thread.sleep(delayMs);
          } catch (InterruptedException e) {
            throw new AssertionError(e);
          }
        }
      }
    }
  }

  /** A sink that drops data after bodyLimit bytes. */
  private static class TruncatingSink extends ForwardingSink {
    private final long bodyLimit;
    private long bytesReceived = 0L;

    TruncatingSink(Sink delegate, long bodyLimit) {
      super(delegate);
      this.bodyLimit = bodyLimit;
    }

    @Override public void write(Buffer source, long byteCount) throws IOException {
      long toRead = Math.min(byteCount, bodyLimit - bytesReceived);
      if (toRead > 0) {
        super.write(source, toRead);
      }
      long toSkip = byteCount - toRead;
      if (toSkip > 0) {
        source.skip(toSkip);
      }
      bytesReceived += byteCount;
    }
  }

  private void readEmptyLine(BufferedSource in) throws IOException {
    String line = in.readUtf8LineStrict();
    if (line.length() != 0) throw new IllegalStateException("Expected empty but was: " + line);
  }

  /**
   * Sets the dispatcher used to match incoming requests to mock responses.
   * The default dispatcher simply serves a fixed sequence of responses from
   * a {@link #enqueue(MockResponse) queue}; custom dispatchers can vary the
   * response based on timing or the content of the request.
   */
  public void setDispatcher(Dispatcher dispatcher) {
    if (dispatcher == null) throw new NullPointerException();
    this.dispatcher = dispatcher;
  }

  @Override public String toString() {
    return "MockWebServer[" + port + "]";
  }

  /** Processes HTTP requests layered over SPDY/3. */
  private class SpdySocketHandler implements IncomingStreamHandler {
    private final Socket socket;
    private final Protocol protocol;
    private final AtomicInteger sequenceNumber = new AtomicInteger();

    private SpdySocketHandler(Socket socket, Protocol protocol) {
      this.socket = socket;
      this.protocol = protocol;
    }

    @Override public void receive(SpdyStream stream) throws IOException {
      RecordedRequest request = readRequest(stream);
      requestQueue.add(request);
      MockResponse response;
      try {
        response = dispatcher.dispatch(request);
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
      writeResponse(stream, response);
      if (logger.isLoggable(Level.INFO)) {
        logger.info(MockWebServer.this + " received request: " + request
            + " and responded: " + response + " protocol is " + protocol.toString());
      }
    }

    private RecordedRequest readRequest(SpdyStream stream) throws IOException {
      List<Header> spdyHeaders = stream.getRequestHeaders();
      Headers.Builder headers = new Headers.Builder();
      String method = "<:method omitted>";
      String path = "<:path omitted>";
      String version = protocol == Protocol.SPDY_3 ? "<:version omitted>" : "HTTP/1.1";
      for (int i = 0, size = spdyHeaders.size(); i < size; i++) {
        ByteString name = spdyHeaders.get(i).name;
        String value = spdyHeaders.get(i).value.utf8();
        if (name.equals(Header.TARGET_METHOD)) {
          method = value;
        } else if (name.equals(Header.TARGET_PATH)) {
          path = value;
        } else if (name.equals(Header.VERSION)) {
          version = value;
        } else {
          headers.add(name.utf8(), value);
        }
      }

      Buffer buffer = new Buffer();
      BufferedSource source = Okio.buffer(stream.getSource());
      buffer.writeAll(source);
      source.close();

      String requestLine = method + ' ' + path + ' ' + version;
      List<Integer> chunkSizes = Collections.emptyList(); // No chunked encoding for SPDY.
      return new RecordedRequest(requestLine, headers.build(), chunkSizes, buffer.size(),
          buffer, sequenceNumber.getAndIncrement(), socket);
    }

    private void writeResponse(SpdyStream stream, MockResponse response) throws IOException {
      if (response.getSocketPolicy() == SocketPolicy.NO_RESPONSE) {
        return;
      }
      List<Header> spdyHeaders = new ArrayList<>();
      String[] statusParts = response.getStatus().split(" ", 2);
      if (statusParts.length != 2) {
        throw new AssertionError("Unexpected status: " + response.getStatus());
      }
      // TODO: constants for well-known header names.
      spdyHeaders.add(new Header(Header.RESPONSE_STATUS, statusParts[1]));
      if (protocol == Protocol.SPDY_3) {
        spdyHeaders.add(new Header(Header.VERSION, statusParts[0]));
      }
      List<String> headers = response.getHeaders();
      for (int i = 0, size = headers.size(); i < size; i++) {
        String header = headers.get(i);
        String[] headerParts = header.split(":", 2);
        if (headerParts.length != 2) {
          throw new AssertionError("Unexpected header: " + header);
        }
        spdyHeaders.add(new Header(headerParts[0], headerParts[1]));
      }

      Source body = response.getBody();
      long bodyLength = response.getBodyLength();
      boolean hasBody = body != null && bodyLength != 0;

      boolean closeStreamAfterHeaders = hasBody || !response.getPushPromises().isEmpty();
      stream.reply(spdyHeaders, closeStreamAfterHeaders);
      pushPromises(stream, response.getPushPromises());
      if (hasBody) {
        sleepIfDelayed(response);
        BufferedSink sink = Okio.buffer(new ThrottledSink(stream.getSink(), response));
        sink.writeAll(body);
        sink.close();
      } else if (closeStreamAfterHeaders) {
        stream.close(ErrorCode.NO_ERROR);
      }
    }

    private void pushPromises(SpdyStream stream, List<PushPromise> promises) throws IOException {
      for (PushPromise pushPromise : promises) {
        List<Header> pushedHeaders = new ArrayList<>();
        pushedHeaders.add(new Header(stream.getConnection().getProtocol() == Protocol.SPDY_3
            ? Header.TARGET_HOST
            : Header.TARGET_AUTHORITY, getUrl(pushPromise.getPath()).getHost()));
        pushedHeaders.add(new Header(Header.TARGET_METHOD, pushPromise.getMethod()));
        pushedHeaders.add(new Header(Header.TARGET_PATH, pushPromise.getPath()));
        Headers pushPromiseHeaders = pushPromise.getHeaders();
        for (int i = 0, size = pushPromiseHeaders.size(); i < size; i++) {
          pushedHeaders.add(new Header(pushPromiseHeaders.name(i), pushPromiseHeaders.value(i)));
        }
        String requestLine = pushPromise.getMethod() + ' ' + pushPromise.getPath() + " HTTP/1.1";
        List<Integer> chunkSizes = Collections.emptyList(); // No chunked encoding for SPDY.
        requestQueue.add(new RecordedRequest(requestLine, pushPromiseHeaders, chunkSizes, 0,
            new Buffer(), sequenceNumber.getAndIncrement(), socket));
        MockResponse response = pushPromise.getResponse();
        Source pushedBody = response.getBody();
        long pushedBodyLength = response.getBodyLength();
        SpdyStream pushedStream = stream.getConnection()
            .pushStream(stream.getId(), pushedHeaders, pushedBody != null && pushedBodyLength != 0);
        writeResponse(pushedStream, response);
      }
    }
  }
}
