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

package okhttp3.mockwebserver;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
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
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.Internal;
import okhttp3.internal.NamedRunnable;
import okhttp3.internal.Util;
import okhttp3.internal.http.HttpMethod;
import okhttp3.internal.http2.ErrorCode;
import okhttp3.internal.http2.Header;
import okhttp3.internal.http2.Http2Connection;
import okhttp3.internal.http2.Http2Stream;
import okhttp3.internal.http2.Settings;
import okhttp3.internal.platform.Platform;
import okhttp3.internal.ws.RealWebSocket;
import okhttp3.internal.ws.WebSocketProtocol;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ByteString;
import okio.Okio;
import okio.Sink;
import okio.Timeout;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import static java.util.concurrent.TimeUnit.SECONDS;
import static okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST;
import static okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END;
import static okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START;
import static okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_REQUEST_BODY;
import static okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY;
import static okhttp3.mockwebserver.SocketPolicy.FAIL_HANDSHAKE;
import static okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE;
import static okhttp3.mockwebserver.SocketPolicy.RESET_STREAM_AT_START;
import static okhttp3.mockwebserver.SocketPolicy.SHUTDOWN_INPUT_AT_END;
import static okhttp3.mockwebserver.SocketPolicy.SHUTDOWN_OUTPUT_AT_END;
import static okhttp3.mockwebserver.SocketPolicy.UPGRADE_TO_SSL_AT_END;

/**
 * A scriptable web server. Callers supply canned responses and the server replays them upon request
 * in sequence.
 */
public final class MockWebServer implements TestRule, Closeable {
  static {
    Internal.initializeInstanceForTests();
  }

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
  private final Set<Http2Connection> openConnections =
      Collections.newSetFromMap(new ConcurrentHashMap<Http2Connection, Boolean>());
  private final AtomicInteger requestCount = new AtomicInteger();
  private long bodyLimit = Long.MAX_VALUE;
  private ServerSocketFactory serverSocketFactory = ServerSocketFactory.getDefault();
  private ServerSocket serverSocket;
  private SSLSocketFactory sslSocketFactory;
  private ExecutorService executor;
  private boolean tunnelProxy;
  private Dispatcher dispatcher = new QueueDispatcher();

  private int port = -1;
  private InetSocketAddress inetSocketAddress;
  private boolean protocolNegotiationEnabled = true;
  private List<Protocol> protocols = Util.immutableList(Protocol.HTTP_2, Protocol.HTTP_1_1);

  private boolean started;

  private synchronized void maybeStart() {
    if (started) return;
    try {
      start();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override public Statement apply(final Statement base, Description description) {
    return new Statement() {
      @Override public void evaluate() throws Throwable {
        maybeStart();
        try {
          base.evaluate();
        } finally {
          try {
            shutdown();
          } catch (IOException e) {
            logger.log(Level.WARNING, "MockWebServer shutdown failed", e);
          }
        }
      }
    };
  }

  public int getPort() {
    maybeStart();
    return port;
  }

  public String getHostName() {
    maybeStart();
    return inetSocketAddress.getHostName();
  }

  public Proxy toProxyAddress() {
    maybeStart();
    InetSocketAddress address = new InetSocketAddress(inetSocketAddress.getAddress(), getPort());
    return new Proxy(Proxy.Type.HTTP, address);
  }

  public void setServerSocketFactory(ServerSocketFactory serverSocketFactory) {
    if (executor != null) {
      throw new IllegalStateException(
          "setServerSocketFactory() must be called before start()");
    }
    this.serverSocketFactory = serverSocketFactory;
  }

  /**
   * Returns a URL for connecting to this server.
   *
   * @param path the request path, such as "/".
   */
  public HttpUrl url(String path) {
    return new HttpUrl.Builder()
        .scheme(sslSocketFactory != null ? "https" : "http")
        .host(getHostName())
        .port(getPort())
        .build()
        .resolve(path);
  }

  /**
   * Sets the number of bytes of the POST body to keep in memory to the given limit.
   */
  public void setBodyLimit(long maxBodyLength) {
    this.bodyLimit = maxBodyLength;
  }

  /**
   * Sets whether ALPN is used on incoming HTTPS connections to negotiate a protocol like HTTP/1.1
   * or HTTP/2. Call this method to disable negotiation and restrict connections to HTTP/1.1.
   */
  public void setProtocolNegotiationEnabled(boolean protocolNegotiationEnabled) {
    this.protocolNegotiationEnabled = protocolNegotiationEnabled;
  }

  /**
   * Indicates the protocols supported by ALPN on incoming HTTPS connections. This list is ignored
   * when {@link #setProtocolNegotiationEnabled negotiation is disabled}.
   *
   * @param protocols the protocols to use, in order of preference. The list must contain
   * {@linkplain Protocol#HTTP_1_1}. It must not contain null.
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
   *
   * @param tunnelProxy true to expect the HTTP CONNECT method before negotiating TLS.
   */
  public void useHttps(SSLSocketFactory sslSocketFactory, boolean tunnelProxy) {
    this.sslSocketFactory = sslSocketFactory;
    this.tunnelProxy = tunnelProxy;
  }

  /**
   * Awaits the next HTTP request, removes it, and returns it. Callers should use this to verify the
   * request was sent as intended. This method will block until the request is available, possibly
   * forever.
   *
   * @return the head of the request queue
   */
  public RecordedRequest takeRequest() throws InterruptedException {
    return requestQueue.take();
  }

  /**
   * Awaits the next HTTP request (waiting up to the specified wait time if necessary), removes it,
   * and returns it. Callers should use this to verify the request was sent as intended within the
   * given time.
   *
   * @param timeout how long to wait before giving up, in units of {@code unit}
   * @param unit a {@code TimeUnit} determining how to interpret the {@code timeout} parameter
   * @return the head of the request queue
   */
  public RecordedRequest takeRequest(long timeout, TimeUnit unit) throws InterruptedException {
    return requestQueue.poll(timeout, unit);
  }

  /**
   * Returns the number of HTTP requests received thus far by this server. This may exceed the
   * number of HTTP connections when connection reuse is in practice.
   */
  public int getRequestCount() {
    return requestCount.get();
  }

  /**
   * Scripts {@code response} to be returned to a request made in sequence. The first request is
   * served by the first enqueued response; the second request by the second enqueued response; and
   * so on.
   *
   * @throws ClassCastException if the default dispatcher has been replaced with {@link
   * #setDispatcher(Dispatcher)}.
   */
  public void enqueue(MockResponse response) {
    ((QueueDispatcher) dispatcher).enqueueResponse(response.clone());
  }

  /** Equivalent to {@code start(0)}. */
  public void start() throws IOException {
    start(0);
  }

  /**
   * Starts the server on the loopback interface for the given port.
   *
   * @param port the port to listen to, or 0 for any available port. Automated tests should always
   * use port 0 to avoid flakiness when a specific port is unavailable.
   */
  public void start(int port) throws IOException {
    start(InetAddress.getByName("localhost"), port);
  }

  /**
   * Starts the server on the given address and port.
   *
   * @param inetAddress the address to create the server socket on
   * @param port the port to listen to, or 0 for any available port. Automated tests should always
   * use port 0 to avoid flakiness when a specific port is unavailable.
   */
  public void start(InetAddress inetAddress, int port) throws IOException {
    start(new InetSocketAddress(inetAddress, port));
  }

  /**
   * Starts the server and binds to the given socket address.
   *
   * @param inetSocketAddress the socket address to bind the server on
   */
  private synchronized void start(InetSocketAddress inetSocketAddress) throws IOException {
    if (started) throw new IllegalStateException("start() already called");
    started = true;

    executor = Executors.newCachedThreadPool(Util.threadFactory("MockWebServer", false));
    this.inetSocketAddress = inetSocketAddress;
    serverSocket = serverSocketFactory.createServerSocket();
    // Reuse if the user specified a port
    serverSocket.setReuseAddress(inetSocketAddress.getPort() != 0);
    serverSocket.bind(inetSocketAddress, 50);

    port = serverSocket.getLocalPort();
    executor.execute(new NamedRunnable("MockWebServer %s", port) {
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
        for (Iterator<Http2Connection> s = openConnections.iterator(); s.hasNext(); ) {
          Util.closeQuietly(s.next());
          s.remove();
        }
        dispatcher.shutdown();
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

  public synchronized void shutdown() throws IOException {
    if (!started) return;
    if (serverSocket == null) throw new IllegalStateException("shutdown() before start()");

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
          logger.info(
              MockWebServer.this + " connection from " + raw.getInetAddress() + " failed: " + e);
        } catch (Exception e) {
          logger.log(Level.SEVERE,
              MockWebServer.this + " connection from " + raw.getInetAddress() + " crashed", e);
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
            protocol = protocolString != null ? Protocol.get(protocolString) : Protocol.HTTP_1_1;
          }
          openClientSockets.remove(raw);
        } else {
          socket = raw;
        }

        if (protocol == Protocol.HTTP_2) {
          FramedSocketHandler framedSocketListener = new FramedSocketHandler(socket, protocol);
          Http2Connection connection = new Http2Connection.Builder(false)
              .socket(socket)
              .listener(framedSocketListener)
              .build();
          connection.start();
          openConnections.add(connection);
          openClientSockets.remove(socket);
          return;
        } else if (protocol != Protocol.HTTP_1_1) {
          throw new AssertionError();
        }

        BufferedSource source = Okio.buffer(Okio.source(socket));
        BufferedSink sink = Okio.buffer(Okio.sink(socket));

        while (processOneRequest(socket, source, sink)) {
        }

        if (sequenceNumber == 0) {
          logger.warning(MockWebServer.this
              + " connection from "
              + raw.getInetAddress()
              + " didn't make a request");
        }

        source.close();
        sink.close();
        socket.close();
        openClientSockets.remove(socket);
      }

      /**
       * Respond to CONNECT requests until a SWITCH_TO_SSL_AT_END response is
       * dispatched.
       */
      private void createTunnel() throws IOException, InterruptedException {
        BufferedSource source = Okio.buffer(Okio.source(raw));
        BufferedSink sink = Okio.buffer(Okio.sink(raw));
        while (true) {
          SocketPolicy socketPolicy = dispatcher.peek().getSocketPolicy();
          if (!processOneRequest(raw, source, sink)) {
            throw new IllegalStateException("Tunnel without any CONNECT!");
          }
          if (socketPolicy == UPGRADE_TO_SSL_AT_END) return;
        }
      }

      /**
       * Reads a request and writes its response. Returns true if further calls should be attempted
       * on the socket.
       */
      private boolean processOneRequest(Socket socket, BufferedSource source, BufferedSink sink)
          throws IOException, InterruptedException {
        RecordedRequest request = readRequest(socket, source, sink, sequenceNumber);
        if (request == null) return false;

        requestCount.incrementAndGet();
        requestQueue.add(request);

        MockResponse response = dispatcher.dispatch(request);
        if (response.getSocketPolicy() == DISCONNECT_AFTER_REQUEST) {
          socket.close();
          return false;
        }
        if (response.getSocketPolicy() == NO_RESPONSE) {
          // This read should block until the socket is closed. (Because nobody is writing.)
          if (source.exhausted()) return false;
          throw new ProtocolException("unexpected data");
        }

        boolean reuseSocket = true;
        boolean requestWantsWebSockets = "Upgrade".equalsIgnoreCase(request.getHeader("Connection"))
            && "websocket".equalsIgnoreCase(request.getHeader("Upgrade"));
        boolean responseWantsWebSockets = response.getWebSocketListener() != null;
        if (requestWantsWebSockets && responseWantsWebSockets) {
          handleWebSocketUpgrade(socket, source, sink, request, response);
          reuseSocket = false;
        } else {
          writeHttpResponse(socket, sink, response);
        }

        if (logger.isLoggable(Level.INFO)) {
          logger.info(MockWebServer.this + " received request: " + request
              + " and responded: " + response);
        }

        // See warnings associated with these socket policies in SocketPolicy.
        if (response.getSocketPolicy() == DISCONNECT_AT_END) {
          socket.close();
          return false;
        } else if (response.getSocketPolicy() == SHUTDOWN_INPUT_AT_END) {
          socket.shutdownInput();
        } else if (response.getSocketPolicy() == SHUTDOWN_OUTPUT_AT_END) {
          socket.shutdownOutput();
        }

        sequenceNumber++;
        return reuseSocket;
      }
    });
  }

  private void processHandshakeFailure(Socket raw) throws Exception {
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, new TrustManager[] {UNTRUSTED_TRUST_MANAGER}, new SecureRandom());
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
    RecordedRequest request = new RecordedRequest(
        null, null, null, -1, null, sequenceNumber, socket);
    requestCount.incrementAndGet();
    requestQueue.add(request);
    dispatcher.dispatch(request);
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
      Internal.instance.addLenient(headers, header);
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

    boolean hasBody = false;
    TruncatingBuffer requestBody = new TruncatingBuffer(bodyLimit);
    List<Integer> chunkSizes = new ArrayList<>();
    MockResponse policy = dispatcher.peek();
    if (contentLength != -1) {
      hasBody = contentLength > 0;
      throttledTransfer(policy, socket, source, Okio.buffer(requestBody), contentLength, true);
    } else if (chunked) {
      hasBody = true;
      while (true) {
        int chunkSize = Integer.parseInt(source.readUtf8LineStrict().trim(), 16);
        if (chunkSize == 0) {
          readEmptyLine(source);
          break;
        }
        chunkSizes.add(chunkSize);
        throttledTransfer(policy, socket, source, Okio.buffer(requestBody), chunkSize, true);
        readEmptyLine(source);
      }
    }

    String method = request.substring(0, request.indexOf(' '));
    if (hasBody && !HttpMethod.permitsRequestBody(method)) {
      throw new IllegalArgumentException("Request must not have a body: " + request);
    }

    return new RecordedRequest(request, headers.build(), chunkSizes, requestBody.receivedByteCount,
        requestBody.buffer, sequenceNumber, socket);
  }

  private void handleWebSocketUpgrade(Socket socket, BufferedSource source, BufferedSink sink,
      RecordedRequest request, MockResponse response) throws IOException {
    String key = request.getHeader("Sec-WebSocket-Key");
    String acceptKey = Util.shaBase64(key + WebSocketProtocol.ACCEPT_MAGIC);
    response.setHeader("Sec-WebSocket-Accept", acceptKey);

    writeHttpResponse(socket, sink, response);

    // Adapt the request and response into our Request and Response domain model.
    String scheme = request.getTlsVersion() != null ? "https" : "http";
    String authority = request.getHeader("Host"); // Has host and port.
    final Request fancyRequest = new Request.Builder()
        .url(scheme + "://" + authority + "/")
        .headers(request.getHeaders())
        .build();
    final Response fancyResponse = new Response.Builder()
        .code(Integer.parseInt(response.getStatus().split(" ")[1]))
        .message(response.getStatus().split(" ", 3)[2])
        .headers(response.getHeaders())
        .request(fancyRequest)
        .protocol(Protocol.HTTP_1_1)
        .build();

    String name = request.getPath();
    ThreadPoolExecutor replyExecutor =
        new ThreadPoolExecutor(1, 1, 1, SECONDS, new LinkedBlockingDeque<Runnable>(),
            Util.threadFactory(Util.format("MockWebServer %s WebSocket Replier", name), true));
    replyExecutor.allowCoreThreadTimeOut(true);

    final CountDownLatch connectionClose = new CountDownLatch(1);
    RealWebSocket webSocket =
        new RealWebSocket(false /* is server */, source, sink, new SecureRandom(), replyExecutor,
            response.getWebSocketListener(), fancyResponse, name) {
          @Override protected void shutdown() {
            connectionClose.countDown();
          }
        };

    webSocket.loopReader();

    // Even if messages are no longer being read we need to wait for the connection close signal.
    try {
      connectionClose.await();
    } catch (InterruptedException ignored) {
    }

    replyExecutor.shutdown();
    Util.closeQuietly(sink);
    Util.closeQuietly(source);
  }

  private void writeHttpResponse(Socket socket, BufferedSink sink, MockResponse response)
      throws IOException {
    sink.writeUtf8(response.getStatus());
    sink.writeUtf8("\r\n");

    Headers headers = response.getHeaders();
    for (int i = 0, size = headers.size(); i < size; i++) {
      sink.writeUtf8(headers.name(i));
      sink.writeUtf8(": ");
      sink.writeUtf8(headers.value(i));
      sink.writeUtf8("\r\n");
    }
    sink.writeUtf8("\r\n");
    sink.flush();

    Buffer body = response.getBody();
    if (body == null) return;
    sleepIfDelayed(response);
    throttledTransfer(response, socket, body, sink, body.size(), false);
  }

  private void sleepIfDelayed(MockResponse response) {
    long delayMs = response.getBodyDelay(TimeUnit.MILLISECONDS);
    if (delayMs != 0) {
      try {
        Thread.sleep(delayMs);
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
    }
  }

  /**
   * Transfer bytes from {@code source} to {@code sink} until either {@code byteCount} bytes have
   * been transferred or {@code source} is exhausted. The transfer is throttled according to {@code
   * policy}.
   */
  private void throttledTransfer(MockResponse policy, Socket socket, BufferedSource source,
      BufferedSink sink, long byteCount, boolean isRequest) throws IOException {
    if (byteCount == 0) return;

    Buffer buffer = new Buffer();
    long bytesPerPeriod = policy.getThrottleBytesPerPeriod();
    long periodDelayMs = policy.getThrottlePeriod(TimeUnit.MILLISECONDS);

    long halfByteCount = byteCount / 2;
    boolean disconnectHalfway = isRequest
        ? policy.getSocketPolicy() == DISCONNECT_DURING_REQUEST_BODY
        : policy.getSocketPolicy() == DISCONNECT_DURING_RESPONSE_BODY;

    while (!socket.isClosed()) {
      for (int b = 0; b < bytesPerPeriod; ) {
        // Ensure we do not read past the allotted bytes in this period.
        long toRead = Math.min(byteCount, bytesPerPeriod - b);
        // Ensure we do not read past halfway if the policy will kill the connection.
        if (disconnectHalfway) {
          toRead = Math.min(toRead, byteCount - halfByteCount);
        }

        long read = source.read(buffer, toRead);
        if (read == -1) return;

        sink.write(buffer, read);
        sink.flush();
        b += read;
        byteCount -= read;

        if (disconnectHalfway && byteCount == halfByteCount) {
          socket.close();
          return;
        }

        if (byteCount == 0) return;
      }

      if (periodDelayMs != 0) {
        try {
          Thread.sleep(periodDelayMs);
        } catch (InterruptedException e) {
          throw new AssertionError();
        }
      }
    }
  }

  private void readEmptyLine(BufferedSource source) throws IOException {
    String line = source.readUtf8LineStrict();
    if (line.length() != 0) throw new IllegalStateException("Expected empty but was: " + line);
  }

  /**
   * Sets the dispatcher used to match incoming requests to mock responses. The default dispatcher
   * simply serves a fixed sequence of responses from a {@link #enqueue(MockResponse) queue}; custom
   * dispatchers can vary the response based on timing or the content of the request.
   */
  public void setDispatcher(Dispatcher dispatcher) {
    if (dispatcher == null) throw new NullPointerException();
    this.dispatcher = dispatcher;
  }

  @Override public String toString() {
    return "MockWebServer[" + port + "]";
  }

  @Override public void close() throws IOException {
    shutdown();
  }

  /** A buffer wrapper that drops data after {@code bodyLimit} bytes. */
  private static class TruncatingBuffer implements Sink {
    private final Buffer buffer = new Buffer();
    private long remainingByteCount;
    private long receivedByteCount;

    TruncatingBuffer(long bodyLimit) {
      remainingByteCount = bodyLimit;
    }

    @Override public void write(Buffer source, long byteCount) throws IOException {
      long toRead = Math.min(remainingByteCount, byteCount);
      if (toRead > 0) {
        source.read(buffer, toRead);
      }
      long toSkip = byteCount - toRead;
      if (toSkip > 0) {
        source.skip(toSkip);
      }
      remainingByteCount -= toRead;
      receivedByteCount += byteCount;
    }

    @Override public void flush() throws IOException {
    }

    @Override public Timeout timeout() {
      return Timeout.NONE;
    }

    @Override public void close() throws IOException {
    }
  }

  /** Processes HTTP requests layered over framed protocols. */
  private class FramedSocketHandler extends Http2Connection.Listener {
    private final Socket socket;
    private final Protocol protocol;
    private final AtomicInteger sequenceNumber = new AtomicInteger();

    private FramedSocketHandler(Socket socket, Protocol protocol) {
      this.socket = socket;
      this.protocol = protocol;
    }

    @Override public void onStream(Http2Stream stream) throws IOException {
      MockResponse peekedResponse = dispatcher.peek();
      if (peekedResponse.getSocketPolicy() == RESET_STREAM_AT_START) {
        try {
          dispatchBookkeepingRequest(sequenceNumber.getAndIncrement(), socket);
          stream.close(ErrorCode.fromHttp2(peekedResponse.getHttp2ErrorCode()));
          return;
        } catch (InterruptedException e) {
          throw new InterruptedIOException();
        }
      }

      RecordedRequest request = readRequest(stream);
      requestCount.incrementAndGet();
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

    private RecordedRequest readRequest(Http2Stream stream) throws IOException {
      List<Header> streamHeaders = stream.getRequestHeaders();
      Headers.Builder httpHeaders = new Headers.Builder();
      String method = "<:method omitted>";
      String path = "<:path omitted>";
      for (int i = 0, size = streamHeaders.size(); i < size; i++) {
        ByteString name = streamHeaders.get(i).name;
        String value = streamHeaders.get(i).value.utf8();
        if (name.equals(Header.TARGET_METHOD)) {
          method = value;
        } else if (name.equals(Header.TARGET_PATH)) {
          path = value;
        } else if (protocol == Protocol.HTTP_2) {
          httpHeaders.add(name.utf8(), value);
        } else {
          throw new IllegalStateException();
        }
      }

      Buffer body = new Buffer();
      body.writeAll(stream.getSource());
      body.close();

      String requestLine = method + ' ' + path + " HTTP/1.1";
      List<Integer> chunkSizes = Collections.emptyList(); // No chunked encoding for HTTP/2.
      return new RecordedRequest(requestLine, httpHeaders.build(), chunkSizes, body.size(), body,
          sequenceNumber.getAndIncrement(), socket);
    }

    private void writeResponse(Http2Stream stream, MockResponse response) throws IOException {
      Settings settings = response.getSettings();
      if (settings != null) {
        stream.getConnection().setSettings(settings);
      }

      if (response.getSocketPolicy() == NO_RESPONSE) {
        return;
      }
      List<Header> http2Headers = new ArrayList<>();
      String[] statusParts = response.getStatus().split(" ", 2);
      if (statusParts.length != 2) {
        throw new AssertionError("Unexpected status: " + response.getStatus());
      }
      // TODO: constants for well-known header names.
      http2Headers.add(new Header(Header.RESPONSE_STATUS, statusParts[1]));
      Headers headers = response.getHeaders();
      for (int i = 0, size = headers.size(); i < size; i++) {
        http2Headers.add(new Header(headers.name(i), headers.value(i)));
      }

      Buffer body = response.getBody();
      boolean closeStreamAfterHeaders = body != null || !response.getPushPromises().isEmpty();
      stream.reply(http2Headers, closeStreamAfterHeaders);
      pushPromises(stream, response.getPushPromises());
      if (body != null) {
        BufferedSink sink = Okio.buffer(stream.getSink());
        sleepIfDelayed(response);
        throttledTransfer(response, socket, body, sink, bodyLimit, false);
        sink.close();
      } else if (closeStreamAfterHeaders) {
        stream.close(ErrorCode.NO_ERROR);
      }
    }

    private void pushPromises(Http2Stream stream, List<PushPromise> promises) throws IOException {
      for (PushPromise pushPromise : promises) {
        List<Header> pushedHeaders = new ArrayList<>();
        pushedHeaders.add(new Header(Header.TARGET_AUTHORITY, url(pushPromise.path()).host()));
        pushedHeaders.add(new Header(Header.TARGET_METHOD, pushPromise.method()));
        pushedHeaders.add(new Header(Header.TARGET_PATH, pushPromise.path()));
        Headers pushPromiseHeaders = pushPromise.headers();
        for (int i = 0, size = pushPromiseHeaders.size(); i < size; i++) {
          pushedHeaders.add(new Header(pushPromiseHeaders.name(i), pushPromiseHeaders.value(i)));
        }
        String requestLine = pushPromise.method() + ' ' + pushPromise.path() + " HTTP/1.1";
        List<Integer> chunkSizes = Collections.emptyList(); // No chunked encoding for HTTP/2.
        requestQueue.add(new RecordedRequest(requestLine, pushPromise.headers(), chunkSizes, 0,
            new Buffer(), sequenceNumber.getAndIncrement(), socket));
        boolean hasBody = pushPromise.response().getBody() != null;
        Http2Stream pushedStream =
            stream.getConnection().pushStream(stream.getId(), pushedHeaders, hasBody);
        writeResponse(pushedStream, pushPromise.response());
      }
    }
  }
}
