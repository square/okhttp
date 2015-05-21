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
package com.squareup.okhttp.ws;

import com.squareup.okhttp.Call;
import com.squareup.okhttp.Callback;
import com.squareup.okhttp.Connection;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.internal.Internal;
import com.squareup.okhttp.internal.NamedRunnable;
import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.ws.RealWebSocket;
import com.squareup.okhttp.internal.ws.WebSocketProtocol;
import java.io.IOException;
import java.net.ProtocolException;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ByteString;

import static java.util.concurrent.TimeUnit.SECONDS;

public final class WebSocketCall {
  /**
   * Prepares the {@code request} to create a web socket at some point in the future.
   */
  public static WebSocketCall create(OkHttpClient client, Request request) {
    return new WebSocketCall(client, request);
  }

  private final Request request;
  private final Call call;
  private final Random random;
  private final String key;

  WebSocketCall(OkHttpClient client, Request request) {
    this(client, request, new SecureRandom());
  }

  WebSocketCall(OkHttpClient client, Request request, Random random) {
    if (!"GET".equals(request.method())) {
      throw new IllegalArgumentException("Request must be GET: " + request.method());
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
        .header("Upgrade", "websocket")
        .header("Connection", "Upgrade")
        .header("Sec-WebSocket-Key", key)
        .header("Sec-WebSocket-Version", "13")
        .build();
    this.request = request;

    call = client.newCall(request);
  }

  /**
   * Schedules the request to be executed at some point in the future.
   *
   * <p>The {@link OkHttpClient#getDispatcher dispatcher} defines when the request will run:
   * usually immediately unless there are several other requests currently being executed.
   *
   * <p>This client will later call back {@code responseCallback} with either an HTTP response or a
   * failure exception. If you {@link #cancel} a request before it completes the callback will not
   * be invoked.
   *
   * @throws IllegalStateException when the call has already been executed.
   */
  public void enqueue(final WebSocketListener listener) {
    Callback responseCallback = new Callback() {
      @Override public void onResponse(Response response) throws IOException {
        try {
          createWebSocket(response, listener);
        } catch (IOException e) {
          listener.onFailure(e, response);
        }
      }

      @Override public void onFailure(Request request, IOException e) {
        listener.onFailure(e, null);
      }
    };
    // TODO call.enqueue(responseCallback, true);
    Internal.instance.callEnqueue(call, responseCallback, true);
  }

  /** Cancels the request, if possible. Requests that are already complete cannot be canceled. */
  public void cancel() {
    call.cancel();
  }

  private void createWebSocket(Response response, WebSocketListener listener)
      throws IOException {
    if (response.code() != 101) {
      // TODO call.engine.releaseConnection();
      Internal.instance.callEngineReleaseConnection(call);
      throw new ProtocolException("Expected HTTP 101 response but was '"
          + response.code()
          + " "
          + response.message()
          + "'");
    }

    String headerConnection = response.header("Connection");
    if (!"Upgrade".equalsIgnoreCase(headerConnection)) {
      throw new ProtocolException(
          "Expected 'Connection' header value 'Upgrade' but was '" + headerConnection + "'");
    }
    String headerUpgrade = response.header("Upgrade");
    if (!"websocket".equalsIgnoreCase(headerUpgrade)) {
      throw new ProtocolException(
          "Expected 'Upgrade' header value 'websocket' but was '" + headerUpgrade + "'");
    }
    String headerAccept = response.header("Sec-WebSocket-Accept");
    String acceptExpected = Util.shaBase64(key + WebSocketProtocol.ACCEPT_MAGIC);
    if (!acceptExpected.equals(headerAccept)) {
      throw new ProtocolException("Expected 'Sec-WebSocket-Accept' header value '"
          + acceptExpected
          + "' but was '"
          + headerAccept
          + "'");
    }

    // TODO connection = call.engine.getConnection();
    Connection connection = Internal.instance.callEngineGetConnection(call);
    // TODO if (!connection.clearOwner()) {
    if (!Internal.instance.clearOwner(connection)) {
      throw new IllegalStateException("Unable to take ownership of connection.");
    }

    BufferedSource source = Internal.instance.connectionRawSource(connection);
    BufferedSink sink = Internal.instance.connectionRawSink(connection);

    final RealWebSocket webSocket =
        ConnectionWebSocket.create(response, connection, source, sink, random, listener);

    // TODO connection.setOwner(webSocket);
    Internal.instance.connectionSetOwner(connection, webSocket);

    listener.onOpen(webSocket, response);

    // Start a dedicated thread for reading the web socket.
    new Thread(new NamedRunnable("OkHttp WebSocket reader %s", request.urlString()) {
      @Override protected void execute() {
        while (webSocket.readMessage()) {
        }
      }
    }).start();
  }

  // Keep static so that the WebSocketCall instance can be garbage collected.
  private static class ConnectionWebSocket extends RealWebSocket {
    static RealWebSocket create(Response response, Connection connection, BufferedSource source,
        BufferedSink sink, Random random, WebSocketListener listener) {
      String url = response.request().urlString();
      ThreadPoolExecutor replyExecutor =
          new ThreadPoolExecutor(1, 1, 1, SECONDS, new LinkedBlockingDeque<Runnable>(),
              Util.threadFactory(String.format("OkHttp %s WebSocket", url), true));
      replyExecutor.allowCoreThreadTimeOut(true);

      return new ConnectionWebSocket(connection, source, sink, random, replyExecutor, listener,
          url);
    }

    private final Connection connection;

    private ConnectionWebSocket(Connection connection, BufferedSource source, BufferedSink sink,
        Random random, Executor replyExecutor, WebSocketListener listener, String url) {
      super(true /* is client */, source, sink, random, replyExecutor, listener, url);
      this.connection = connection;
    }

    @Override protected void closeConnection() throws IOException {
      // TODO connection.closeIfOwnedBy(this);
      Internal.instance.closeIfOwnedBy(connection, this);
    }
  }
}
