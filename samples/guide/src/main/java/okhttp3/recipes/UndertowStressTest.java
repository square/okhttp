/*
 * Copyright (C) 2018 Square, Inc.
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
package okhttp3.recipes;

import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.io.Receiver;
import io.undertow.io.Sender;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.TlsVersion;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import okio.BufferedSink;
import okio.BufferedSource;

public class UndertowStressTest implements HttpHandler, Receiver.ErrorCallback {
  final HttpUrl serverUrl = HttpUrl.get("https://localhost:8444/");
  final byte[] data = new byte[65536];
  Undertow server;
  OkHttpClient client;
  private int requestSize;
  private int responseSize;
  private AtomicLong steps = new AtomicLong();

  public UndertowStressTest(int requestSize, int responseSize) {
    this.requestSize = requestSize;
    this.responseSize = responseSize;
    Arrays.fill(data, (byte) 'a');
  }

  private void print(String symbol) {
    if (steps.incrementAndGet() % 80 == 0) {
      System.out.println(symbol);
    } else {
      System.out.print(symbol);
    }
  }

  public void start() throws Exception {
    String localhost = InetAddress.getByName("localhost").getCanonicalHostName();

    HeldCertificate localhostCertificate = new HeldCertificate.Builder()
        .addSubjectAlternativeName(localhost)
        .build();

    HandshakeCertificates serverCertificates = new HandshakeCertificates.Builder()
        .heldCertificate(localhostCertificate)
        .build();

    server = Undertow.builder()
        .setServerOption(UndertowOptions.ENABLE_HTTP2, true)
        .setServerOption(UndertowOptions.HTTP2_SETTINGS_INITIAL_WINDOW_SIZE, 16 * 1024 * 1024)
        .setServerOption(UndertowOptions.HTTP2_PADDING_SIZE, 100)
        .setServerOption(UndertowOptions.HTTP2_SETTINGS_MAX_CONCURRENT_STREAMS, 16)
        .addHttpsListener(serverUrl.port(), "localhost", serverCertificates.sslContext())
        .setHandler(this)
        .build();

    HandshakeCertificates clientCertificates = new HandshakeCertificates.Builder()
        .addTrustedCertificate(localhostCertificate.certificate())
        .build();

    client = new OkHttpClient.Builder()
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager())
        .build();

    server.start();
  }

  @Override public void handleRequest(HttpServerExchange exchange) throws Exception {
    HttpUrl url = HttpUrl.get(exchange.getRequestURL() + "?" + exchange.getQueryString());
    String threadId = url.pathSegments().get(0);
    String requestId = url.pathSegments().get(1);
    int responseSize = Integer.parseInt(url.queryParameter("size"));
    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
    exchange.getRequestReceiver().receivePartialBytes(new Receiver.PartialBytesCallback() {
      @Override public void handle(HttpServerExchange exchange, byte[] message, boolean last) {
        if (last) {
          writeToSender(exchange.getResponseSender(), responseSize);
          print("S");
        }
      }
    }, this);
  }

  @Override public void error(HttpServerExchange exchange, IOException e) {
    e.printStackTrace();
  }

  public void callServer(
      int threadId, int requestId, int requestSize, int responseSize) throws IOException {
    Call call = client.newCall(new Request.Builder()
        .url(serverUrl.resolve("/" + threadId + "/" + requestId + "?size=" + responseSize))
        .post(requestBody(threadId, requestId, requestSize))
        .build());
    try (Response response = call.execute()) {
      if (response.handshake().tlsVersion() != TlsVersion.TLS_1_3) {
        throw new IOException("unexpected handshake " + response.handshake());
      }
      if (response.protocol() != Protocol.HTTP_2) {
        throw new IOException("unexpected protocol " + response.protocol());
      }
      if (response.code() != 200) {
        throw new IOException("unexpected response code " + response.code());
      }
      BufferedSource source = response.body().source();
      String s = source.readUtf8(responseSize);
      //source.skip(responseSize);
      if (!source.exhausted()) {
        throw new IOException("unexpected response size");
      }
    } catch (IOException e) {
      e.printStackTrace(); // Don't wait for the whole test to complete before dumping errors.
      throw e;
    }
  }

  private void runStressTest(int threadCount, int threadRequestCount) {
    ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < threadCount; t++) {
      int threadId = t;
      futures.add(executorService.submit(new Callable<Void>() {
        @Override public Void call() throws Exception {
          Thread.currentThread().setName("callServer-" + threadId + "/" + threadCount);
          for (int r = 0; r < threadRequestCount; r++) {
            callServer(threadId, r, requestSize, responseSize);
            print("C");
          }
          return null;
        }
      }));
    }
    for (int i = 0; i < threadCount; i++) {
      try {
        futures.get(i).get();
      } catch (InterruptedException | ExecutionException e) {
        e.printStackTrace();
      }
    }
    System.out.println();
    System.out.println("DONE");
  }

  private RequestBody requestBody(int threadId, int requestId, int requestSize) {
    return new RequestBody() {
      @Override public MediaType contentType() {
        return null;
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        writeToSink(sink, requestSize);
      }
    };
  }

  private void writeToSender(Sender sender, int requestSize) {
    int bytesWritten = 0;
    while (bytesWritten < requestSize) {
      int sliceSize = Math.min(data.length, requestSize - bytesWritten);
      sender.send(ByteBuffer.wrap(data, 0, sliceSize));
      bytesWritten += sliceSize;
    }
  }

  private void writeToSink(BufferedSink sink, int requestSize) throws IOException {
    int bytesWritten = 0;
    while (bytesWritten < requestSize) {
      int sliceSize = Math.min(data.length, requestSize - bytesWritten);
      sink.write(data, 0, sliceSize);
      bytesWritten += sliceSize;
    }
  }

  public static void main(String... args) throws Exception {
    int requestSize = 33 * 1024 * 1024;
    int responseSize = 65536;
    UndertowStressTest undertowStressTest = new UndertowStressTest(requestSize, responseSize);
    undertowStressTest.start();
    undertowStressTest.runStressTest(32, 50);
  }
}
