/*
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

package okhttp3.mockwebserver.internal.http2;

import java.io.File;
import java.io.IOException;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.Headers;
import okhttp3.Protocol;
import okhttp3.internal.concurrent.TaskRunner;
import okhttp3.internal.http2.Header;
import okhttp3.internal.http2.Http2Connection;
import okhttp3.internal.http2.Http2Stream;
import okhttp3.internal.platform.Platform;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;

import static java.util.Arrays.asList;
import static okhttp3.internal.Util.closeQuietly;
import static okhttp3.internal.platform.Platform.INFO;
import static okhttp3.tls.internal.TlsUtil.localhost;

/** A basic HTTP/2 server that serves the contents of a local directory. */
public final class Http2Server extends Http2Connection.Listener {
  static final Logger logger = Logger.getLogger(Http2Server.class.getName());

  private final File baseDirectory;
  private final SSLSocketFactory sslSocketFactory;

  public Http2Server(File baseDirectory, SSLSocketFactory sslSocketFactory) {
    this.baseDirectory = baseDirectory;
    this.sslSocketFactory = sslSocketFactory;
  }

  private void run() throws Exception {
    ServerSocket serverSocket = new ServerSocket(8888);
    serverSocket.setReuseAddress(true);

    while (true) {
      Socket socket = null;
      try {
        socket = serverSocket.accept();

        SSLSocket sslSocket = doSsl(socket);
        String protocolString = Platform.get().getSelectedProtocol(sslSocket);
        Protocol protocol = protocolString != null ? Protocol.get(protocolString) : null;
        if (protocol != Protocol.HTTP_2) {
          throw new ProtocolException("Protocol " + protocol + " unsupported");
        }
        Http2Connection connection = new Http2Connection.Builder(false, TaskRunner.INSTANCE)
            .socket(sslSocket)
            .listener(this)
            .build();
        connection.start();
      } catch (IOException e) {
        logger.log(Level.INFO, "Http2Server connection failure: " + e);
        if (socket != null) {
          closeQuietly(socket);
        }
      } catch (Exception e) {
        logger.log(Level.WARNING, "Http2Server unexpected failure", e);
        if (socket != null) {
          closeQuietly(socket);
        }
      }
    }
  }

  private SSLSocket doSsl(Socket socket) throws IOException {
    SSLSocket sslSocket = (SSLSocket) sslSocketFactory.createSocket(
        socket, socket.getInetAddress().getHostAddress(), socket.getPort(), true);
    sslSocket.setUseClientMode(false);
    Platform.get().configureTlsExtensions(sslSocket,
        Collections.singletonList(Protocol.HTTP_2));
    sslSocket.startHandshake();
    return sslSocket;
  }

  @Override public void onStream(Http2Stream stream) throws IOException {
    try {
      Headers requestHeaders = stream.takeHeaders();
      String path = null;
      for (int i = 0, size = requestHeaders.size(); i < size; i++) {
        if (requestHeaders.name(i).equals(Header.TARGET_PATH_UTF8)) {
          path = requestHeaders.value(i);
          break;
        }
      }

      if (path == null) {
        // TODO: send bad request error
        throw new AssertionError();
      }

      File file = new File(baseDirectory + path);

      if (file.isDirectory()) {
        serveDirectory(stream, file.listFiles());
      } else if (file.exists()) {
        serveFile(stream, file);
      } else {
        send404(stream, path);
      }
    } catch (IOException e) {
      Platform.get().log("Failure serving Http2Stream: " + e.getMessage(), INFO, null);
    }
  }

  private void send404(Http2Stream stream, String path) throws IOException {
    List<Header> responseHeaders = asList(
        new Header(":status", "404"),
        new Header(":version", "HTTP/1.1"),
        new Header("content-type", "text/plain")
    );
    stream.writeHeaders(responseHeaders, false, false);
    BufferedSink out = Okio.buffer(stream.getSink());
    out.writeUtf8("Not found: " + path);
    out.close();
  }

  private void serveDirectory(Http2Stream stream, File[] files) throws IOException {
    List<Header> responseHeaders = asList(
        new Header(":status", "200"),
        new Header(":version", "HTTP/1.1"),
        new Header("content-type", "text/html; charset=UTF-8")
    );
    stream.writeHeaders(responseHeaders, false, false);
    BufferedSink out = Okio.buffer(stream.getSink());
    for (File file : files) {
      String target = file.isDirectory() ? (file.getName() + "/") : file.getName();
      out.writeUtf8("<a href='" + target + "'>" + target + "</a><br>");
    }
    out.close();
  }

  private void serveFile(Http2Stream stream, File file) throws IOException {
    List<Header> responseHeaders = asList(
        new Header(":status", "200"),
        new Header(":version", "HTTP/1.1"),
        new Header("content-type", contentType(file))
    );
    stream.writeHeaders(responseHeaders, false, false);
    try (Source source = Okio.source(file); BufferedSink sink = Okio.buffer(stream.getSink())) {
      sink.writeAll(source);
    }
  }

  private String contentType(File file) {
    if (file.getName().endsWith(".css")) return "text/css";
    if (file.getName().endsWith(".gif")) return "image/gif";
    if (file.getName().endsWith(".html")) return "text/html";
    if (file.getName().endsWith(".jpeg")) return "image/jpeg";
    if (file.getName().endsWith(".jpg")) return "image/jpeg";
    if (file.getName().endsWith(".js")) return "application/javascript";
    if (file.getName().endsWith(".png")) return "image/png";
    return "text/plain";
  }

  public static void main(String... args) throws Exception {
    if (args.length != 1 || args[0].startsWith("-")) {
      System.out.println("Usage: Http2Server <base directory>");
      return;
    }

    Http2Server server = new Http2Server(new File(args[0]),
        localhost().sslContext().getSocketFactory());
    server.run();
  }
}
