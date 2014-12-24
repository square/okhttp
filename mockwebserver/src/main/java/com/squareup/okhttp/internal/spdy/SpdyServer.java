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

package com.squareup.okhttp.internal.spdy;

import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.internal.Platform;
import com.squareup.okhttp.internal.SslContextBuilder;
import com.squareup.okhttp.internal.Util;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;

/** A basic SPDY/HTTP_2 server that serves the contents of a local directory. */
public final class SpdyServer implements IncomingStreamHandler {
  private final List<Protocol> spdyProtocols = Util.immutableList(Protocol.HTTP_2, Protocol.SPDY_3);

  private final File baseDirectory;
  private SSLSocketFactory sslSocketFactory;
  private Protocol protocol;

  public SpdyServer(File baseDirectory) {
    this.baseDirectory = baseDirectory;
  }

  public void useHttps(SSLSocketFactory sslSocketFactory) {
    this.sslSocketFactory = sslSocketFactory;
  }

  private void run() throws Exception {
    ServerSocket serverSocket = new ServerSocket(8888);
    serverSocket.setReuseAddress(true);

    while (true) {
      Socket socket = serverSocket.accept();
      if (sslSocketFactory != null) {
        socket = doSsl(socket);
      }
      new SpdyConnection.Builder(false, socket).protocol(protocol).handler(this).build();
    }
  }

  private Socket doSsl(Socket socket) throws IOException {
    SSLSocket sslSocket =
        (SSLSocket) sslSocketFactory.createSocket(socket, socket.getInetAddress().getHostAddress(),
            socket.getPort(), true);
    sslSocket.setUseClientMode(false);
    Platform.get().configureTlsExtensions(sslSocket, null, spdyProtocols);
    sslSocket.startHandshake();
    String protocolString = Platform.get().getSelectedProtocol(sslSocket);
    protocol = protocolString != null ? Protocol.get(protocolString) : null;
    if (protocol == null || !spdyProtocols.contains(protocol)) {
      throw new IllegalStateException("Protocol " + protocol + " unsupported");
    }
    return sslSocket;
  }

  @Override public void receive(final SpdyStream stream) throws IOException {
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
      serveDirectory(stream, file.list());
    } else if (file.exists()) {
      serveFile(stream, file);
    } else {
      send404(stream, path);
    }
  }

  private void send404(SpdyStream stream, String path) throws IOException {
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

  private void serveDirectory(SpdyStream stream, String[] files) throws IOException {
    List<Header> responseHeaders = Arrays.asList(
        new Header(":status", "200"),
        new Header(":version", "HTTP/1.1"),
        new Header("content-type", "text/html; charset=UTF-8")
    );
    stream.reply(responseHeaders, true);
    BufferedSink out = Okio.buffer(stream.getSink());
    for (String file : files) {
      out.writeUtf8("<a href='" + file + "'>" + file + "</a><br>");
    }
    out.close();
  }

  private void serveFile(SpdyStream stream, File file) throws IOException {
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
    return file.getName().endsWith(".html") ? "text/html" : "text/plain";
  }

  public static void main(String... args) throws Exception {
    if (args.length != 1 || args[0].startsWith("-")) {
      System.out.println("Usage: SpdyServer <base directory>");
      return;
    }

    SpdyServer server = new SpdyServer(new File(args[0]));
    server.useHttps(SslContextBuilder.localhost().getSocketFactory());
    server.run();
  }
}
