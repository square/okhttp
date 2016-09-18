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
package okhttp3;

import java.io.IOException;
import java.net.ProtocolException;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import okhttp3.internal.Util;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.ws.RealWebSocket;
import okhttp3.internal.ws.WebSocketProtocol;
import okio.ByteString;

import static java.util.concurrent.TimeUnit.SECONDS;

final class RealWebSocketCall implements WebSocketCall {
  private static final List<Protocol> ONLY_HTTP1 = Collections.singletonList(Protocol.HTTP_1_1);

  private final RealCall call;
  private final Random random;
  private final String key;

  RealWebSocketCall(OkHttpClient client, Request request) {
    this(client, request, new SecureRandom());
  }

  RealWebSocketCall(OkHttpClient client, Request request, Random random) {
    if (!"GET".equals(request.method())) {
      throw new IllegalArgumentException("Request must be GET: " + request.method());
    }
    this.random = random;

    byte[] nonce = new byte[16];
    random.nextBytes(nonce);
    key = ByteString.of(nonce).base64();

    client = client.newBuilder()
        .readTimeout(0, SECONDS) // i.e., no timeout because this is a long-lived connection.
        .writeTimeout(0, SECONDS) // i.e., no timeout because this is a long-lived connection.
        .protocols(ONLY_HTTP1)
        .build();

    request = request.newBuilder()
        .header("Upgrade", "websocket")
        .header("Connection", "Upgrade")
        .header("Sec-WebSocket-Key", key)
        .header("Sec-WebSocket-Version", "13")
        .build();

    call = new RealCall(client, request, true /* for web socket */);
  }

  @Override public void enqueue(final WebSocketListener listener) {
    Callback responseCallback = new Callback() {
      @Override public void onResponse(Call call, Response response) {
        try {
          createWebSocket(response, listener);
        } catch (IOException e) {
          listener.onFailure(e, response);
        }
      }

      @Override public void onFailure(Call call, IOException e) {
        listener.onFailure(e, null);
      }
    };
    call.enqueue(responseCallback);
  }

  @Override public void cancel() {
    call.cancel();
  }

  private void createWebSocket(Response response, WebSocketListener listener) throws IOException {
    if (response.code() != 101) {
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

    StreamAllocation streamAllocation = call.streamAllocation();
    RealWebSocket webSocket = StreamWebSocket.create(streamAllocation, response, random, listener);

    listener.onOpen(webSocket, response);

    while (webSocket.readMessage()) {
    }
  }

  // Keep static so that the WebSocketCall instance can be garbage collected.
  private static class StreamWebSocket extends RealWebSocket {
    static RealWebSocket create(StreamAllocation streamAllocation, Response response,
        Random random, WebSocketListener listener) {
      String url = response.request().url().redact().toString();
      ThreadPoolExecutor replyExecutor =
          new ThreadPoolExecutor(1, 1, 1, SECONDS, new LinkedBlockingDeque<Runnable>(),
              Util.threadFactory(Util.format("OkHttp %s WebSocket", url), true));
      replyExecutor.allowCoreThreadTimeOut(true);

      return new StreamWebSocket(streamAllocation, random, replyExecutor, listener, url);
    }

    private final StreamAllocation streamAllocation;
    private final ExecutorService replyExecutor;

    private StreamWebSocket(StreamAllocation streamAllocation,
        Random random, ExecutorService replyExecutor, WebSocketListener listener, String url) {
      super(true /* is client */, streamAllocation.connection().source,
          streamAllocation.connection().sink, random, replyExecutor, listener, url);
      this.streamAllocation = streamAllocation;
      this.replyExecutor = replyExecutor;
    }

    @Override protected void close() throws IOException {
      replyExecutor.shutdown();
      streamAllocation.noNewStreams();
      streamAllocation.streamFinished(true, streamAllocation.codec());
    }
  }
}
