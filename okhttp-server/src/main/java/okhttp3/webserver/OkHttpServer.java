/*
 * Copyright (C) 2016 Square, Inc.
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

package okhttp3.webserver;

import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.Protocol;
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

import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * A modern HTTP web server.
 */
public final class OkHttpServer implements Closeable {
  private static final Logger logger = Logger.getLogger(OkHttpServer.class.getName());

  private final Set<Socket> openClientSockets =
      Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
  private final Set<Http2Connection> openConnections =
      Collections.newSetFromMap(new ConcurrentHashMap<Http2Connection, Boolean>());
  private long bodyLimit = Long.MAX_VALUE;
  private ServerSocketFactory serverSocketFactory = ServerSocketFactory.getDefault();
  private ServerSocket serverSocket;
  private SSLSocketFactory sslSocketFactory;
  private ExecutorService executor;
  private boolean tunnelProxy;
  private Dispatcher dispatcher = new Dispatcher() {
    @Override
    public ServerResponse dispatch(ClientRequest clientRequest) throws InterruptedException {
      return new ServerResponse();
    }
  };

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

  @Override
  public String toString() {
    return "OkHttpServer[" + port + "]";
  }

  @Override
  public void close() throws IOException {
    shutdown();
  }

  /**
   * Sets the dispatcher used to match incoming requests to handlers.
   */
  public void setDispatcher(Dispatcher dispatcher) {
    if (dispatcher == null) throw new NullPointerException();
    this.dispatcher = dispatcher;
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
   *                  {@linkplain Protocol#HTTP_1_1}. It must not contain null.
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
   * Equivalent to {@code start(0)}.
   */
  public void start() throws IOException {
    start(0);
  }

  /**
   * Starts the server on the loopback interface for the given port.
   *
   * @param port the port to listen to, or 0 for any available port. Automated tests should always
   *             use port 0 to avoid flakiness when a specific port is unavailable.
   */
  public void start(int port) throws IOException {
    start(InetAddress.getByName("localhost"), port);
  }

  /**
   * Starts the server on the given address and port.
   *
   * @param inetAddress the address to create the server socket on
   * @param port        the port to listen to, or 0 for any available port. Automated tests
   *                    should always
   *                    use port 0 to avoid flakiness when a specific port is unavailable.
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
    if (started) throw new IllegalStateException("Already started");
    started = true;

    executor = Executors.newCachedThreadPool(Util.threadFactory("OkHttpServer", false));
    this.inetSocketAddress = inetSocketAddress;
    serverSocket = serverSocketFactory.createServerSocket();
    // Reuse if the user specified a port
    serverSocket.setReuseAddress(inetSocketAddress.getPort() != 0);
    // TODO: The backlog should be configurable.
    serverSocket.bind(inetSocketAddress, 50);

    port = serverSocket.getLocalPort();
    executor.execute(new NamedRunnable("OkHttpServer %s", port) {
      @Override
      protected void execute() {
        try {
          logger.info(OkHttpServer.this + " starting to accept connections");
          acceptConnections();
        } catch (Throwable e) {
          logger.log(Level.WARNING, OkHttpServer.this + " failed unexpectedly", e);
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
            logger.info(OkHttpServer.this + " done accepting connections: " + e.getMessage());
            return;
          }
          openClientSockets.add(socket);
          serveConnection(socket);
        }
      }
    });
  }

  private synchronized void shutdown() throws IOException {
    if (!started) return;
    if (serverSocket == null) throw new IllegalStateException("shutdown() before start()");

    // Cause acceptConnections() to break out.
    serverSocket.close();

    // Await shutdown.
    try {
      if (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
        throw new IOException("Gave up waiting for executor to shut down");
      }
    } catch (InterruptedException e) {
      throw new AssertionError();
    }
  }

  private void serveConnection(final Socket raw) {
    executor.execute(new NamedRunnable("OkHttpServer %s", raw.getRemoteSocketAddress()) {
      int sequenceNumber = 0;

      @Override
      protected void execute() {
        try {
          processConnection();
        } catch (IOException e) {
          logger.info(
              OkHttpServer.this + " connection from " + raw.getInetAddress() + " failed: " + e);
        } catch (Exception e) {
          logger.log(Level.SEVERE,
              OkHttpServer.this + " connection from " + raw.getInetAddress() + " crashed", e);
        }
      }

      public void processConnection() throws Exception {
        Protocol protocol = Protocol.HTTP_1_1;
        Socket socket;
        if (sslSocketFactory != null) {
          if (tunnelProxy) {
            createTunnel();
          }
          socket = sslSocketFactory.createSocket(raw, raw
                  .getInetAddress()
                  .getHostAddress(),
              raw.getPort(), true);
          SSLSocket sslSocket = (SSLSocket) socket;
          sslSocket.setUseClientMode(false);
          openClientSockets.add(socket);

          if (protocolNegotiationEnabled) {
            Platform
                .get()
                .configureTlsExtensions(sslSocket, null, protocols);
          }

          sslSocket.startHandshake();

          if (protocolNegotiationEnabled) {
            String protocolString = Platform
                .get()
                .getSelectedProtocol(sslSocket);
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
          throw new AssertionError("Unsupported protocol: " + protocol);
        }

        BufferedSource source = Okio.buffer(Okio.source(socket));
        BufferedSink sink = Okio.buffer(Okio.sink(socket));

        while (processOneRequest(socket, source, sink)) {
        }

        if (sequenceNumber == 0) {
          logger.warning(OkHttpServer.this + " connection from " + raw.getInetAddress()
              + " didn't make a request");
        }

        source.close();
        sink.close();
        socket.close();
        openClientSockets.remove(socket);
      }

      /**
       * Respond to CONNECT requests until an UPGRADE_TO_SSL_AT_END response is
       * dispatched.
       */
      private void createTunnel() throws IOException, InterruptedException {
        BufferedSource source = Okio.buffer(Okio.source(raw));
        BufferedSink sink = Okio.buffer(Okio.sink(raw));
        // TODO: This is probably wrong.
        if (!processOneRequest(raw, source, sink)) {
          throw new IllegalStateException("Tunnel without any CONNECT!");
        }
      }

      /**
       * Reads a request and writes its response. Returns true if further calls should be attempted
       * on the socket.
       */
      private boolean processOneRequest(Socket socket, BufferedSource source, BufferedSink sink)
          throws IOException, InterruptedException {
        ClientRequest clientRequest = readRequest(socket, source, sink, sequenceNumber);
        if (clientRequest == null) return false;

        ServerResponse serverResponse = dispatcher.dispatch(clientRequest);
        boolean reuseSocket = true;
        boolean requestWantsWebSockets = "Upgrade"
            .equalsIgnoreCase(clientRequest.getHeader("Connection"))
            && "websocket".equalsIgnoreCase(clientRequest.getHeader("Upgrade"));
        boolean responseWantsWebSockets = serverResponse.getWebSocketListener() != null;
        if (requestWantsWebSockets && responseWantsWebSockets) {
          handleWebSocketUpgrade(socket, source, sink, clientRequest, serverResponse);
          reuseSocket = false;
        } else {
          writeHttpResponse(socket, sink, serverResponse);
        }

        if (logger.isLoggable(Level.INFO)) {
          logger.info(OkHttpServer.this + " received clientRequest: " + clientRequest
              + " and responded: " + serverResponse);
        }

        sequenceNumber++;
        return reuseSocket;
      }
    });
  }

  /**
   * @param sequenceNumber the index of this request on this connection.
   */
  private ClientRequest readRequest(Socket socket, BufferedSource source, BufferedSink sink,
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
        contentLength = Long.parseLong(header
            .substring(15)
            .trim());
      }
      if (lowercaseHeader.startsWith("transfer-encoding:")
          && lowercaseHeader
          .substring(18)
          .trim()
          .equals("chunked")) {
        chunked = true;
      }
      if (lowercaseHeader.startsWith("expect:")
          && lowercaseHeader
          .substring(7)
          .trim()
          .equals("100-continue")) {
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
    if (contentLength != -1) {
      hasBody = contentLength > 0;
      transfer(socket, source, Okio.buffer(requestBody), contentLength);
    } else if (chunked) {
      hasBody = true;
      while (true) {
        int chunkSize = Integer.parseInt(source.readUtf8LineStrict().trim(), 16);
        if (chunkSize == 0) {
          readEmptyLine(source);
          break;
        }
        chunkSizes.add(chunkSize);
        transfer(socket, source, Okio.buffer(requestBody), chunkSize);
        readEmptyLine(source);
      }
    }

    String method = request.substring(0, request.indexOf(' '));
    if (hasBody && !HttpMethod.permitsRequestBody(method)) {
      throw new IllegalArgumentException("ClientRequest must not have a body: " + request);
    }

    return new ClientRequest(request, headers.build(), chunkSizes, requestBody.receivedByteCount,
        requestBody.buffer, sequenceNumber, socket);
  }

  private void handleWebSocketUpgrade(
      Socket socket, BufferedSource source, BufferedSink sink,
      ClientRequest clientRequest, ServerResponse serverResponse) throws IOException {
    String key = clientRequest.getHeader("Sec-WebSocket-Key");
    String acceptKey = Util.shaBase64(key + WebSocketProtocol.ACCEPT_MAGIC);
    serverResponse.setHeader("Sec-WebSocket-Accept", acceptKey);

    writeHttpResponse(socket, sink, serverResponse);

    // Adapt the clientRequest and serverResponse into our ClientRequest and ServerResponse
    // domain model.
    String scheme = clientRequest.getTlsVersion() != null ? "https" : "http";
    String authority = clientRequest.getHeader("Host"); // Has host and port.
    final okhttp3.Request fancyRequest = new okhttp3.Request.Builder()
        .url(scheme + "://" + authority + "/")
        .headers(clientRequest.getHeaders())
        .build();
    final okhttp3.Response fancyResponse = new okhttp3.Response.Builder()
        .code(Integer.parseInt(serverResponse
            .getStatus()
            .split(" ")[1]))
        .message(serverResponse
            .getStatus()
            .split(" ", 3)[2])
        .headers(serverResponse.getHeaders())
        .request(fancyRequest)
        .protocol(Protocol.HTTP_1_1)
        .build();

    String name = clientRequest.getPath();
    ThreadPoolExecutor replyExecutor =
        new ThreadPoolExecutor(1, 1, 1, SECONDS, new LinkedBlockingDeque<Runnable>(),
            Util.threadFactory(Util.format("OkHttpServer %s WebSocket Replier", name), true));
    replyExecutor.allowCoreThreadTimeOut(true);

    final CountDownLatch connectionClose = new CountDownLatch(1);
    RealWebSocket webSocket = new RealWebSocket(false, source, sink, new SecureRandom(),
        replyExecutor, serverResponse.getWebSocketListener(), fancyResponse, name) {
      @Override
      protected void shutdown() {
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

  private void writeHttpResponse(Socket socket, BufferedSink sink, ServerResponse serverResponse)
      throws IOException {
    sink.writeUtf8(serverResponse.getStatus());
    sink.writeUtf8("\r\n");

    Headers headers = serverResponse.getHeaders();
    for (int i = 0, size = headers.size(); i < size; i++) {
      sink.writeUtf8(headers.name(i));
      sink.writeUtf8(": ");
      sink.writeUtf8(headers.value(i));
      sink.writeUtf8("\r\n");
    }
    sink.writeUtf8("\r\n");
    sink.flush();

    Buffer body = serverResponse.getBody();
    if (body == null) return;
    transfer(socket, body, sink, body.size());
  }

  /**
   * Transfer bytes from {@code source} to {@code sink} until either {@code byteCount} bytes have
   * been transferred or {@code source} is exhausted.
   */
  private void transfer(Socket socket, BufferedSource source, BufferedSink sink, long byteCount)
      throws IOException {
    if (byteCount == 0) return;

    Buffer buffer = new Buffer();
    long written = 0;
    while (!socket.isClosed() && written < byteCount) {
      long read = source.read(buffer, byteCount);
      if (read == -1) return;

      sink.write(buffer, read);
      sink.flush();
      written += read;
    }
  }

  private void readEmptyLine(BufferedSource source) throws IOException {
    String line = source.readUtf8LineStrict();
    if (line.length() != 0) throw new IllegalStateException("Expected empty but was: " + line);
  }

  /**
   * A buffer wrapper that drops data after {@code bodyLimit} bytes.
   */
  private static class TruncatingBuffer implements Sink {
    private final Buffer buffer = new Buffer();
    private long remainingByteCount;
    private long receivedByteCount;

    TruncatingBuffer(long bodyLimit) {
      remainingByteCount = bodyLimit;
    }

    @Override
    public void write(Buffer source, long byteCount) throws IOException {
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

    @Override
    public void flush() throws IOException {
    }

    @Override
    public Timeout timeout() {
      return Timeout.NONE;
    }

    @Override
    public void close() throws IOException {
    }
  }

  /**
   * Processes HTTP requests layered over framed protocols.
   */
  private class FramedSocketHandler extends Http2Connection.Listener {
    private final Socket socket;
    private final Protocol protocol;
    private final AtomicInteger sequenceNumber = new AtomicInteger();

    private FramedSocketHandler(Socket socket, Protocol protocol) {
      this.socket = socket;
      this.protocol = protocol;
    }

    @Override
    public void onStream(Http2Stream stream) throws IOException {
      ClientRequest clientRequest = readRequest(stream);
      ServerResponse serverResponse;
      try {
        serverResponse = dispatcher.dispatch(clientRequest);
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
      writeResponse(stream, serverResponse);
      if (logger.isLoggable(Level.INFO)) {
        logger.info(OkHttpServer.this + " received clientRequest: " + clientRequest
            + " and responded: " + serverResponse + " protocol is " + protocol.toString());
      }
    }

    private ClientRequest readRequest(Http2Stream stream) throws IOException {
      List<Header> streamHeaders = stream.getRequestHeaders();
      Headers.Builder httpHeaders = new Headers.Builder();
      String method = "<:method omitted>";
      String path = "<:path omitted>";
      for (Header streamHeader : streamHeaders) {
        ByteString name = streamHeader.name;
        String value = streamHeader.value.utf8();
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
      return new ClientRequest(requestLine, httpHeaders.build(), chunkSizes, body.size(), body,
          sequenceNumber.getAndIncrement(), socket);
    }

    private void writeResponse(Http2Stream stream, ServerResponse serverResponse)
        throws IOException {
      Settings settings = serverResponse.getSettings();
      if (settings != null) {
        stream.getConnection().setSettings(settings);
      }

      List<Header> http2Headers = new ArrayList<>();
      String[] statusParts = serverResponse.getStatus().split(" ", 2);
      if (statusParts.length != 2) {
        throw new AssertionError("Unexpected status: " + serverResponse.getStatus());
      }
      // TODO: constants for well-known header names.
      http2Headers.add(new Header(Header.RESPONSE_STATUS, statusParts[1]));
      Headers headers = serverResponse.getHeaders();
      for (int i = 0, size = headers.size(); i < size; i++) {
        http2Headers.add(new Header(headers.name(i), headers.value(i)));
      }

      Buffer body = serverResponse.getBody();
      boolean closeStreamAfterHeaders = body != null || !serverResponse.getPushPromises().isEmpty();
      stream.reply(http2Headers, closeStreamAfterHeaders);
      pushPromises(stream, serverResponse.getPushPromises());
      if (body != null) {
        BufferedSink sink = Okio.buffer(stream.getSink());
        transfer(socket, body, sink, bodyLimit);
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
        boolean hasBody = pushPromise.response().getBody() != null;
        Http2Stream pushedStream = stream.getConnection()
            .pushStream(stream.getId(), pushedHeaders, hasBody);
        writeResponse(pushedStream, pushPromise.response());
      }
    }
  }
}
