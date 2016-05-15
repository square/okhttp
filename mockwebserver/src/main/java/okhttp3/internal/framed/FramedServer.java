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

package okhttp3.internal.framed;

import java.io.File;
import java.io.IOException;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.Protocol;
import okhttp3.internal.Platform;
import okhttp3.internal.SslContextBuilder;
import okhttp3.internal.Util;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;

import static okhttp3.internal.Platform.INFO;

/** A basic SPDY/HTTP_2 server that serves the contents of a local directory. */
public final class FramedServer extends FramedConnection.Listener {
  static final Logger logger = Logger.getLogger(FramedServer.class.getName());

  private final List<Protocol> framedProtocols =
      Util.immutableList(Protocol.HTTP_2, Protocol.SPDY_3);

  private final File baseDirectory;
  private final SSLSocketFactory sslSocketFactory;

  public FramedServer(File baseDirectory, SSLSocketFactory sslSocketFactory) {
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
        if (protocol == null || !framedProtocols.contains(protocol)) {
          throw new ProtocolException("Protocol " + protocol + " unsupported");
        }
        FramedConnection framedConnection = new FramedConnection.Builder(false)
            .socket(sslSocket)
            .protocol(protocol)
            .listener(this)
            .build();
        framedConnection.start();
      } catch (IOException e) {
        logger.log(Level.INFO, "FramedServer connection failure: " + e);
        Util.closeQuietly(socket);
      } catch (Exception e) {
        logger.log(Level.WARNING, "FramedServer unexpected failure", e);
        Util.closeQuietly(socket);
      }
    }
  }

  private SSLSocket doSsl(Socket socket) throws IOException {
    SSLSocket sslSocket = (SSLSocket) sslSocketFactory.createSocket(
        socket, socket.getInetAddress().getHostAddress(), socket.getPort(), true);
    sslSocket.setUseClientMode(false);
    Platform.get().configureTlsExtensions(sslSocket, null, framedProtocols);
    sslSocket.startHandshake();
    return sslSocket;
  }

  @Override public void onStream(final FramedStream stream) throws IOException {
    try {
      List<Header> requestHeaders = stream.getRequestHeaders();
      String path = null;
      for (int i = 0, size = requestHeaders.size(); i < size; i++) {
        if (requestHeaders.get(i).name.equals(Header.TARGET_PATH)) {
          path = requestHeaders.get(i).value.utf8();
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
      Platform.get().log(INFO, "Failure serving FramedStream: " + e.getMessage(), null);
    }
  }

  private void send404(FramedStream stream, String path) throws IOException {
    List<Header> responseHeaders = Arrays.asList(
        new Header(":status", "404"),
        new Header(":version", "HTTP/1.1"),
        new Header("content-type", "text/plain")
    );
    stream.reply(responseHeaders, true);
    BufferedSink out = Okio.buffer(stream.getSink());
    out.writeUtf8("Not found: " + path);
    out.close();
  }

  private void serveDirectory(FramedStream stream, File[] files) throws IOException {
    List<Header> responseHeaders = Arrays.asList(
        new Header(":status", "200"),
        new Header(":version", "HTTP/1.1"),
        new Header("content-type", "text/html; charset=UTF-8")
    );
    stream.reply(responseHeaders, true);
    BufferedSink out = Okio.buffer(stream.getSink());
    for (File file : files) {
      String target = file.isDirectory() ? (file.getName() + "/") : file.getName();
      out.writeUtf8("<a href='" + target + "'>" + target + "</a><br>");
    }
    out.close();
  }

  private void serveFile(FramedStream stream, File file) throws IOException {
    List<Header> responseHeaders = Arrays.asList(
        new Header(":status", "200"),
        new Header(":version", "HTTP/1.1"),
        new Header("content-type", contentType(file))
    );
    stream.reply(responseHeaders, true);
    Source source = Okio.source(file);
    try {
      BufferedSink out = Okio.buffer(stream.getSink());
      out.writeAll(source);
      out.close();
    } finally {
      Util.closeQuietly(source);
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
      System.out.println("Usage: FramedServer <base directory>");
      return;
    }

    FramedServer server = new FramedServer(new File(args[0]),
        SslContextBuilder.localhost().sslContext.getSocketFactory());
    server.run();
  }
}
