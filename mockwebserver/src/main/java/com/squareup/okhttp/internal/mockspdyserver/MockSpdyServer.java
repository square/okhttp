/*
 * Copyright (C) 2013 Square, Inc.
 * Copyright (C) 2011 The Android Open Source Project
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

package com.squareup.okhttp.internal.mockspdyserver;

import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.QueueDispatcher;
import com.squareup.okhttp.mockwebserver.RecordedRequest;
import com.squareup.okhttp.internal.Platform;
import com.squareup.okhttp.internal.spdy.IncomingStreamHandler;
import com.squareup.okhttp.internal.spdy.SpdyConnection;
import com.squareup.okhttp.internal.spdy.SpdyStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** A scriptable spdy/3 + HTTP server. */
public final class MockSpdyServer {
  private static final byte[] NPN_PROTOCOLS = new byte[] { 6, 's', 'p', 'd', 'y', '/', '3', };
  private static final Logger logger = Logger.getLogger(MockSpdyServer.class.getName());
  private SSLSocketFactory sslSocketFactory;
  private QueueDispatcher dispatcher = new QueueDispatcher();
  private ServerSocket serverSocket;
  private final Set<Socket> openClientSockets =
      Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
  private int port = -1;
  private final BlockingQueue<RecordedRequest> requestQueue =
      new LinkedBlockingQueue<RecordedRequest>();

  public MockSpdyServer(SSLSocketFactory sslSocketFactory) {
    this.sslSocketFactory = sslSocketFactory;
  }

  public String getHostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      throw new AssertionError();
    }
  }

  public int getPort() {
    if (port == -1) {
      throw new IllegalStateException("Cannot retrieve port before calling play()");
    }
    return port;
  }

  public URL getUrl(String path) {
    try {
      return new URL("https://" + getHostName() + ":" + getPort() + path);
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
   * Awaits the next HTTP request, removes it, and returns it. Callers should
   * use this to verify the request sent was as intended.
   */
  public RecordedRequest takeRequest() throws InterruptedException {
    return requestQueue.take();
  }

  public void play() throws IOException {
    serverSocket = new ServerSocket(0);
    serverSocket.setReuseAddress(true);
    port = serverSocket.getLocalPort();

    Thread acceptThread = new Thread("MockSpdyServer-accept-" + port) {
      @Override public void run() {
        int sequenceNumber = 0;
        try {
          acceptConnections(sequenceNumber);
        } catch (Throwable e) {
          logger.log(Level.WARNING, "MockWebServer connection failed", e);
        }

        // This gnarly block of code will release all sockets and
        // all thread, even if any close fails.
        try {
          serverSocket.close();
        } catch (Throwable e) {
          logger.log(Level.WARNING, "MockWebServer server socket close failed", e);
        }
        for (Iterator<Socket> s = openClientSockets.iterator(); s.hasNext(); ) {
          try {
            s.next().close();
            s.remove();
          } catch (Throwable e) {
            logger.log(Level.WARNING, "MockWebServer socket close failed", e);
          }
        }
      }
    };
    acceptThread.start();
  }

  public void enqueue(MockResponse response) {
    dispatcher.enqueueResponse(response);
  }

  private void acceptConnections(int sequenceNumber) throws Exception {
    while (true) {
      Socket socket;
      try {
        socket = serverSocket.accept();
      } catch (SocketException e) {
        return;
      }
      openClientSockets.add(socket);
      new SocketHandler(sequenceNumber++, socket).serve();
    }
  }

  public void shutdown() throws IOException {
    if (serverSocket != null) {
      serverSocket.close(); // should cause acceptConnections() to break out
    }
  }

  private class SocketHandler implements IncomingStreamHandler {
    private final int sequenceNumber;
    private Socket socket;

    private SocketHandler(int sequenceNumber, Socket socket) throws IOException {
      this.socket = socket;
      this.sequenceNumber = sequenceNumber;
    }

    public void serve() throws IOException {
      if (sslSocketFactory != null) {
        socket = doSsl(socket);
      }
      new SpdyConnection.Builder(false, socket).handler(this).build();
    }

    private Socket doSsl(Socket socket) throws IOException {
      SSLSocket sslSocket = (SSLSocket) sslSocketFactory.createSocket(socket,
          socket.getInetAddress().getHostAddress(), socket.getPort(), true);
      sslSocket.setUseClientMode(false);
      Platform.get().setNpnProtocols(sslSocket, NPN_PROTOCOLS);
      return sslSocket;
    }

    @Override public void receive(final SpdyStream stream) throws IOException {
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
          bodyOut.toByteArray(), sequenceNumber, socket);
    }

    private void writeResponse(SpdyStream stream, MockResponse response) throws IOException {
      List<String> spdyHeaders = new ArrayList<String>();
      String[] statusParts = response.getStatus().split(" ", 2);
      if (statusParts.length != 2) {
        throw new AssertionError("Unexpected status: " + response.getStatus());
      }
      spdyHeaders.add(":status");
      spdyHeaders.add(statusParts[1]);
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
}
