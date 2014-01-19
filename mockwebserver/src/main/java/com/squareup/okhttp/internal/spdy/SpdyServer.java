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
import com.squareup.okhttp.internal.ByteString;
import com.squareup.okhttp.internal.SslContextBuilder;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.eclipse.jetty.npn.NextProtoNego;

import static com.squareup.okhttp.internal.Util.headerEntries;

/** A basic SPDY server that serves the contents of a local directory. */
public final class SpdyServer implements IncomingStreamHandler {
  private final File baseDirectory;
  private SSLSocketFactory sslSocketFactory;

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
      new SpdyConnection.Builder(false, socket).handler(this).build();
    }
  }

  private Socket doSsl(Socket socket) throws IOException {
    SSLSocket sslSocket =
        (SSLSocket) sslSocketFactory.createSocket(socket, socket.getInetAddress().getHostAddress(),
            socket.getPort(), true);
    sslSocket.setUseClientMode(false);
    NextProtoNego.put(sslSocket, new NextProtoNego.ServerProvider() {
      @Override public void unsupported() {
        System.out.println("UNSUPPORTED");
      }
      @Override public List<String> protocols() {
        return Arrays.asList(Protocol.SPDY_3.name.utf8());
      }
      @Override public void protocolSelected(String protocol) {
        System.out.println("PROTOCOL SELECTED: " + protocol);
      }
    });
    return sslSocket;
  }

  @Override public void receive(final SpdyStream stream) throws IOException {
    List<Header> requestHeaders = stream.getRequestHeaders();
    String path = null;
    for (int i = 0; i < requestHeaders.size(); i++) {
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
    List<Header> responseHeaders =
        headerEntries(":status", "404", ":version", "HTTP/1.1", "content-type", "text/plain");
    stream.reply(responseHeaders, true);
    OutputStream out = stream.getOutputStream();
    String text = "Not found: " + path;
    out.write(text.getBytes("UTF-8"));
    out.close();
  }

  private void serveDirectory(SpdyStream stream, String[] files) throws IOException {
    List<Header> responseHeaders =
        headerEntries(":status", "200", ":version", "HTTP/1.1", "content-type",
            "text/html; charset=UTF-8");
    stream.reply(responseHeaders, true);
    OutputStream out = stream.getOutputStream();
    Writer writer = new OutputStreamWriter(out, "UTF-8");
    for (String file : files) {
      writer.write("<a href='" + file + "'>" + file + "</a><br>");
    }
    writer.close();
  }

  private void serveFile(SpdyStream stream, File file) throws IOException {
    InputStream in = new FileInputStream(file);
    byte[] buffer = new byte[8192];
    stream.reply(
        headerEntries(":status", "200", ":version", "HTTP/1.1", "content-type", contentType(file)),
        true);
    OutputStream out = stream.getOutputStream();
    int count;
    while ((count = in.read(buffer)) != -1) {
      out.write(buffer, 0, count);
    }
    out.close();
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
