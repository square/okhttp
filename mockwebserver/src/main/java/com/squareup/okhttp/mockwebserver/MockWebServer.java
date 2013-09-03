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

import com.squareup.okhttp.internal.Platform;
import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.spdy.IncomingStreamHandler;
import com.squareup.okhttp.internal.spdy.SpdyConnection;
import com.squareup.okhttp.internal.spdy.SpdyStream;
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
import java.util.Arrays;
import java.util.Collections;
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

import static com.squareup.okhttp.mockwebserver.SocketPolicy.DISCONNECT_AT_START;
import static com.squareup.okhttp.mockwebserver.SocketPolicy.FAIL_HANDSHAKE;

/**
 * A scriptable web server. Callers supply canned responses and the server
 * replays them upon request in sequence.
 */
public final class MockWebServer {
  private static final byte[] NPN_PROTOCOLS = {
      17, 'H', 'T', 'T', 'P', '-', 'd', 'r', 'a', 'f', 't', '-', '0', '4', '/', '2', '.', '0',
      6, 's', 'p', 'd', 'y', '/', '3',
      8, 'h', 't', 't', 'p', '/', '1', '.', '1'
  };
  private static final byte[] SPDY3 = new byte[] {
      's', 'p', 'd', 'y', '/', '3'
  };
  private static final byte[] HTTP_20_DRAFT_04 = new byte[] {
      'H', 'T', 'T', 'P', '-', 'd', 'r', 'a', 'f', 't', '-', '0', '4', '/', '2', '.', '0'
  };
  private static final byte[] HTTP_11 = new byte[] {
      'h', 't', 't', 'p', '/', '1', '.', '1'
  };

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

  private final BlockingQueue<RecordedRequest> requestQueue =
      new LinkedBlockingQueue<RecordedRequest>();

  /** All map values are Boolean.TRUE. (Collections.newSetFromMap isn't available in Froyo) */
  private final Map<Socket, Boolean> openClientSockets = new ConcurrentHashMap<Socket, Boolean>();
  private final Map<SpdyConnection, Boolean> openSpdyConnections
      = new ConcurrentHashMap<SpdyConnection, Boolean>();
  private final AtomicInteger requestCount = new AtomicInteger();
  private int bodyLimit = Integer.MAX_VALUE;
  private ServerSocket serverSocket;
  private SSLSocketFactory sslSocketFactory;
  private ExecutorService executor;
  private boolean tunnelProxy;
  private Dispatcher dispatcher = new QueueDispatcher();

  private int port = -1;
  private boolean npnEnabled = true;

  public int getPort() {
    if (port == -1) throw new IllegalStateException("Cannot retrieve port before calling play()");
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

  /**
   * Sets the number of bytes of the POST body to keep in memory to the given
   * limit.
   */
  public void setBodyLimit(int maxBodyLength) {
    this.bodyLimit = maxBodyLength;
  }

  /**
   * Sets whether NPN is used on incoming HTTPS connections to negotiate a
   * transport like HTTP/1.1 or SPDY/3. Call this method to disable NPN and
   * SPDY.
   */
  public void setNpnEnabled(boolean npnEnabled) {
    this.npnEnabled = npnEnabled;
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
   * use this to verify the request was sent as intended.
   */
  public RecordedRequest takeRequest() throws InterruptedException {
    return requestQueue.take();
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

        // This gnarly block of code will release all sockets and all thread,
        // even if any close fails.
        Util.closeQuietly(serverSocket);
        for (Iterator<Socket> s = openClientSockets.keySet().iterator(); s.hasNext(); ) {
          Util.closeQuietly(s.next());
          s.remove();
        }
        for (Iterator<SpdyConnection> s = openSpdyConnections.keySet().iterator(); s.hasNext(); ) {
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
            return;
          }
          SocketPolicy socketPolicy = dispatcher.peekSocketPolicy();
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
      serverSocket.close(); // Should cause acceptConnections() to break out.
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
        Transport transport = Transport.HTTP_11;
        Socket socket;
        if (sslSocketFactory != null) {
          if (tunnelProxy) {
            createTunnel();
          }
          SocketPolicy socketPolicy = dispatcher.peekSocketPolicy();
          if (socketPolicy == FAIL_HANDSHAKE) {
            dispatchBookkeepingRequest(sequenceNumber, raw);
            processHandshakeFailure(raw);
            return;
          }
          socket = sslSocketFactory.createSocket(
              raw, raw.getInetAddress().getHostAddress(), raw.getPort(), true);
          SSLSocket sslSocket = (SSLSocket) socket;
          sslSocket.setUseClientMode(false);
          openClientSockets.put(socket, true);

          if (npnEnabled) {
            Platform.get().setNpnProtocols(sslSocket, NPN_PROTOCOLS);
          }

          sslSocket.startHandshake();

          if (npnEnabled) {
            byte[] selectedProtocol = Platform.get().getNpnSelectedProtocol(sslSocket);
            if (selectedProtocol == null || Arrays.equals(selectedProtocol, HTTP_11)) {
              transport = Transport.HTTP_11;
            } else if (Arrays.equals(selectedProtocol, HTTP_20_DRAFT_04)) {
              transport = Transport.HTTP_20_DRAFT_04;
            } else if (Arrays.equals(selectedProtocol, SPDY3)) {
              transport = Transport.SPDY_3;
            } else {
              throw new IllegalStateException(
                  "Unexpected transport: " + new String(selectedProtocol, Util.US_ASCII));
            }
          }
          openClientSockets.remove(raw);
        } else {
          socket = raw;
        }

        if (transport == Transport.SPDY_3 || transport == Transport.HTTP_20_DRAFT_04) {
          SpdySocketHandler spdySocketHandler = new SpdySocketHandler(socket, transport);
          SpdyConnection.Builder builder = new SpdyConnection.Builder(false, socket)
              .handler(spdySocketHandler);
          if (transport == Transport.SPDY_3) {
            builder.spdy3();
          } else {
            builder.http20Draft04();
          }
          SpdyConnection spdyConnection = builder.build();
          openSpdyConnections.put(spdyConnection, Boolean.TRUE);
          openClientSockets.remove(socket);
          spdyConnection.sendConnectionHeader();
          return;
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
       * Respond to CONNECT requests until a SWITCH_TO_SSL_AT_END response is
       * dispatched.
       */
      private void createTunnel() throws IOException, InterruptedException {
        while (true) {
          SocketPolicy socketPolicy = dispatcher.peekSocketPolicy();
          if (!processOneRequest(raw, raw.getInputStream(), raw.getOutputStream())) {
            throw new IllegalStateException("Tunnel without any CONNECT!");
          }
          if (socketPolicy == SocketPolicy.UPGRADE_TO_SSL_AT_END) return;
        }
      }

      /**
       * Reads a request and writes its response. Returns true if a request was
       * processed.
       */
      private boolean processOneRequest(Socket socket, InputStream in, OutputStream out)
          throws IOException, InterruptedException {
        RecordedRequest request = readRequest(socket, in, out, sequenceNumber);
        if (request == null) return false;
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

  private void processHandshakeFailure(Socket raw) throws Exception {
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, new TrustManager[] { UNTRUSTED_TRUST_MANAGER }, new SecureRandom());
    SSLSocketFactory sslSocketFactory = context.getSocketFactory();
    SSLSocket socket =
        (SSLSocket) sslSocketFactory.createSocket(raw, raw.getInetAddress().getHostAddress(),
            raw.getPort(), true);
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
      out.write(("HTTP/1.1 100 Continue\r\n").getBytes(Util.US_ASCII));
      out.write(("Content-Length: 0\r\n").getBytes(Util.US_ASCII));
      out.write(("\r\n").getBytes(Util.US_ASCII));
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

    if (request.startsWith("OPTIONS ")
        || request.startsWith("GET ")
        || request.startsWith("HEAD ")
        || request.startsWith("DELETE ")
        || request.startsWith("TRACE ")
        || request.startsWith("CONNECT ")) {
      if (hasBody) {
        throw new IllegalArgumentException("Request must not have a body: " + request);
      }
    } else if (!request.startsWith("POST ") && !request.startsWith("PUT ")) {
      throw new UnsupportedOperationException("Unexpected method: " + request);
    }

    return new RecordedRequest(request, headers, chunkSizes, requestBody.numBytesReceived,
        requestBody.toByteArray(), sequenceNumber, socket);
  }

  private void writeResponse(OutputStream out, MockResponse response) throws IOException {
    out.write((response.getStatus() + "\r\n").getBytes(Util.US_ASCII));
    for (String header : response.getHeaders()) {
      out.write((header + "\r\n").getBytes(Util.US_ASCII));
    }
    out.write(("\r\n").getBytes(Util.US_ASCII));
    out.flush();

    InputStream in = response.getBodyStream();
    if (in == null) return;
    int bytesPerSecond = response.getBytesPerSecond();

    // Stream data in MTU-sized increments, with a minimum of one packet per second.
    byte[] buffer = bytesPerSecond >= 1452 ? new byte[1452] : new byte[bytesPerSecond];
    long delayMs = bytesPerSecond == Integer.MAX_VALUE
        ? 0
        : (1000 * buffer.length) / bytesPerSecond;

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
      if (count == -1) return;
      out.write(buffer, 0, count);
      length -= count;
    }
  }

  /**
   * Returns the text from {@code in} until the next "\r\n", or null if {@code
   * in} is exhausted.
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

  /** An output stream that drops data after bodyLimit bytes. */
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

  /** Processes HTTP requests layered over SPDY/3. */
  private class SpdySocketHandler implements IncomingStreamHandler {
    private final Socket socket;
    private final Transport transport;

    private SpdySocketHandler(Socket socket, Transport transport) {
      this.socket = socket;
      this.transport = transport;
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
      logger.info("Received request: " + request + " and responded: " + response);
    }

    private RecordedRequest readRequest(SpdyStream stream) throws IOException {
      List<String> spdyHeaders = stream.getRequestHeaders();
      List<String> httpHeaders = new ArrayList<String>();
      String method = "<:method omitted>";
      String path = "<:path omitted>";
      String version = "<:version omitted>";
      for (Iterator<String> i = spdyHeaders.iterator(); i.hasNext(); ) {
        String name = i.next();
        String value = i.next();
        if (":method".equals(name)) {
          method = value;
        } else if (":path".equals(name)) {
          path = value;
        } else if (":version".equals(name)) {
          version = value;
        } else {
          httpHeaders.add(name + ": " + value);
        }
      }

      InputStream bodyIn = stream.getInputStream();
      ByteArrayOutputStream bodyOut = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int count;
      while ((count = bodyIn.read(buffer)) != -1) {
        bodyOut.write(buffer, 0, count);
      }
      bodyIn.close();
      String requestLine = method + ' ' + path + ' ' + version;
      List<Integer> chunkSizes = Collections.emptyList(); // No chunked encoding for SPDY.
      return new RecordedRequest(requestLine, httpHeaders, chunkSizes, bodyOut.size(),
          bodyOut.toByteArray(), 0, socket);
    }

    private void writeResponse(SpdyStream stream, MockResponse response) throws IOException {
      List<String> spdyHeaders = new ArrayList<String>();
      // Split "HTTP/1.1 200 OK" into ["HTTP", "200 OK"] for SPDY/3 and ["HTTP", "200"] for HTTP/2.
      int partCount = (transport == Transport.SPDY_3) ? 2 : 3;
      String[] statusParts = response.getStatus().split(" ", partCount);
      if (statusParts.length != partCount) {
        throw new AssertionError("Unexpected status: " + response.getStatus());
      }
      spdyHeaders.add(":status");
      spdyHeaders.add(statusParts[1]);
      // TODO: no ":version" header for HTTP/2.0, only SPDY/3.
      spdyHeaders.add(":version");
      spdyHeaders.add(statusParts[0]);
      for (String header : response.getHeaders()) {
        String[] headerParts = header.split(":", 2);
        if (headerParts.length != 2) {
          throw new AssertionError("Unexpected header: " + header);
        }
        spdyHeaders.add(headerParts[0].toLowerCase(Locale.US).trim());
        spdyHeaders.add(headerParts[1].trim());
      }
      byte[] body = response.getBody();
      stream.reply(spdyHeaders, body.length > 0);
      if (body.length > 0) {
        stream.getOutputStream().write(body);
        stream.getOutputStream().close();
      }
    }
  }

  enum Transport {
    HTTP_11, SPDY_3, HTTP_20_DRAFT_04
  }
}
