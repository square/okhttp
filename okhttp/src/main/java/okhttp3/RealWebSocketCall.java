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
        StreamWebSocket webSocket;
        try {
          webSocket = create(response, listener);
        } catch (IOException e) {
          listener.onFailure(e, response);
          return;
        }

        webSocket.loopReader();
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

  StreamWebSocket create(Response response, WebSocketListener listener) throws IOException {
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

    String name = response.request().url().redact().toString();
    ThreadPoolExecutor replyExecutor =
        new ThreadPoolExecutor(1, 1, 1, SECONDS, new LinkedBlockingDeque<Runnable>(),
            Util.threadFactory(Util.format("OkHttp %s WebSocket Replier", name), true));
    replyExecutor.allowCoreThreadTimeOut(true);

    StreamAllocation streamAllocation = call.streamAllocation();
    streamAllocation.noNewStreams(); // Web socket connections can't be re-used.
    return new StreamWebSocket(streamAllocation, random, replyExecutor, listener, response, name);
  }

  // Keep static so that the WebSocketCall instance can be garbage collected.
  static final class StreamWebSocket extends RealWebSocket {
    private final StreamAllocation streamAllocation;
    private final ExecutorService executor;

    StreamWebSocket(StreamAllocation streamAllocation, Random random, ExecutorService executor,
        WebSocketListener listener, Response response, String name) {
      super(true /* is client */, streamAllocation.connection().source,
          streamAllocation.connection().sink, random, executor, listener, response, name);
      this.streamAllocation = streamAllocation;
      this.executor = executor;
    }

    @Override protected void shutdown() {
      executor.shutdown();
      streamAllocation.streamFinished(true, streamAllocation.codec());
    }
  }
}
