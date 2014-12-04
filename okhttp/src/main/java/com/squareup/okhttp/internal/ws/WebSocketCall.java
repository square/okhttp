/*
 * Copyright (C) 2014 Square, Inc.
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
package com.squareup.okhttp.internal.ws;

import com.squareup.okhttp.Call;
import com.squareup.okhttp.Connection;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.internal.Internal;
import com.squareup.okhttp.internal.Util;
import java.io.IOException;
import java.net.ProtocolException;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Random;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ByteString;
import okio.Okio;

public class WebSocketCall {
  /** Magic value which must be appended to the {@link #key} in a response header. */
  private static final String ACCEPT_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

  /**
   * Prepares the {@code request} to create a web socket at some point in the future.
   * <p>
   * TODO Move to OkHttpClient as non-static once web sockets are finalized!
   */
  public static WebSocketCall newWebSocketCall(OkHttpClient client, Request request) {
    return new WebSocketCall(client, request, new SecureRandom());
  }

  private final Request request;
  private final Call call;
  private final Random random;
  private final String key;

  protected WebSocketCall(OkHttpClient client, Request request, Random random) {
    if (!"GET".equals(request.method())) {
      throw new IllegalArgumentException("Request must be GET: " + request.method());
    }
    String url = request.urlString();
    String httpUrl;
    if (url.startsWith("ws://")) {
      httpUrl = "http://" + url.substring(5);
    } else if (url.startsWith("wss://")) {
      httpUrl = "https://" + url.substring(6);
    } else if (url.startsWith("http://") || url.startsWith("https://")) {
      httpUrl = url;
    } else {
      throw new IllegalArgumentException(
          "Request url must use 'ws', 'wss', 'http', or 'https' scheme: " + url);
    }

    this.random = random;

    byte[] nonce = new byte[16];
    random.nextBytes(nonce);
    key = ByteString.of(nonce).base64();

    // Copy the client. Otherwise changes (socket factory, redirect policy,
    // etc.) may incorrectly be reflected in the request when it is executed.
    client = client.clone();
    // Force HTTP/1.1 until the WebSocket over HTTP/2 version is finalized.
    client.setProtocols(Collections.singletonList(com.squareup.okhttp.Protocol.HTTP_1_1));

    request = request.newBuilder()
        .url(httpUrl)
        .header("Upgrade", "websocket")
        .header("Connection", "Upgrade")
        .header("Sec-WebSocket-Key", key)
        .header("Sec-WebSocket-Version", "13")
        .build();
    this.request = request;

    // TODO call = new Call(client, request);
    call = Internal.instance.newCall(client, request);
  }

  /**
   * Connects the web socket and blocks until the response can be processed. Once connected all
   * messages from the server are sent to the {@code listener}.
   * <p>
   * Note that transport-layer success (receiving a HTTP response code,
   * headers and body) does not necessarily indicate application-layer success:
   * {@code response} may still indicate an unhappy HTTP response code like 404
   * or 500.
   *
   * @throws IOException if the request could not be executed due to
   *     a connectivity problem or timeout. Because networks can
   *     fail during an exchange, it is possible that the remote server
   *     accepted the request before the failure.
   *
   * @throws IllegalStateException when the web socket has already been connected.
   */
  public WebSocket execute(WebSocketListener listener) throws IOException {
    // TODO Response response = call.getResponse(true);
    Response response = Internal.instance.callGetResponse(call, true);
    validateResponse(response);

    // TODO connection = call.engine.getConnection();
    Connection connection = Internal.instance.callEngineGetConnection(call);
    // TODO if (!connection.clearOwner()) {
    if (!Internal.instance.clearOwner(connection)) {
      throw new IllegalStateException("Unable to take ownership of connection.");
    }

    Socket socket = connection.getSocket();
    BufferedSource source = Okio.buffer(Okio.source(socket));
    BufferedSink sink = Okio.buffer(Okio.sink(socket));

    WebSocketReader reader = new WebSocketReader(true, source, listener);
    WebSocketWriter writer = new WebSocketWriter(true, sink, random);

    WebSocket webSocket = new ConnectionWebSocket(request.urlString(), connection, reader, writer);

    // TODO connection.setOwner(webSocket);
    Internal.instance.connectionSetOwner(connection, webSocket);

    return webSocket;
  }

  // TODO public void enqueue(some WebSocketListener subclass?) { ... }
  // - adds a onConnected(WebSocket) callback to WebSocketListener
  // - on what thread is onConnected invoked?
  // - does this require fully-async writing?

  private void validateResponse(Response response) throws IOException {
    if (response.code() != 101) {
      // TODO call.engine.releaseConnection();
      Internal.instance.callEngineReleaseConnection(call);
      throw new ProtocolException(
          "Expected HTTP 101 response but was: " + response.code() + " " + response.message());
    }

    String headerConnection = response.header("Connection");
    if (!"Upgrade".equalsIgnoreCase(headerConnection)) {
      throw new ProtocolException(
          "Expected 'Connection' header value 'Upgrade' but was: " + headerConnection);
    }
    String headerUpgrade = response.header("Upgrade");
    if (!"websocket".equalsIgnoreCase(headerUpgrade)) {
      throw new ProtocolException(
          "Expected 'Upgrade' header value 'websocket' but was: " + headerUpgrade);
    }
    String headerAccept = response.header("Sec-WebSocket-Accept");
    String acceptExpected = Util.shaBase64(key + ACCEPT_MAGIC);
    if (!acceptExpected.equals(headerAccept)) {
      throw new ProtocolException("Expected 'Sec-WebSocket-Accept' header value '"
          + acceptExpected
          + "' but was: "
          + headerAccept);
    }
  }

  // Keep static so that the WebSocketCall instance can be garbage collected.
  private static class ConnectionWebSocket extends WebSocket {
    private final Connection connection;

    public ConnectionWebSocket(String name, Connection connection, WebSocketReader reader,
        WebSocketWriter writer) {
      super(name, reader, writer);
      this.connection = connection;
    }

    @Override protected void closeConnection() throws IOException {
      // TODO connection.closeIfOwnedBy(this);
      Internal.instance.closeIfOwnedBy(connection, this);
    }
  }
}
