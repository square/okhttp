/*
 * Copyright (C) 2017 Square, Inc.
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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Connection;
import okhttp3.EventListener;
import okhttp3.Handshake;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jetbrains.annotations.NotNull;

public final class PrintEvents {
  private final OkHttpClient client = new OkHttpClient.Builder()
      .eventListenerFactory(PrintingEventListener.FACTORY)
      .build();

  public void run() throws Exception {
    Request washingtonPostRequest = new Request.Builder()
        .url("https://www.washingtonpost.com/")
        .build();
    client.newCall(washingtonPostRequest).enqueue(new Callback() {
      @Override public void onFailure(@NotNull Call call, @NotNull IOException e) {
      }

      @Override public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
        try (ResponseBody body = response.body()) {
          // Consume and discard the response body.
          body.source().readByteString();
        }
      }
    });

    Request newYorkTimesRequest = new Request.Builder()
        .url("https://www.nytimes.com/")
        .build();
    client.newCall(newYorkTimesRequest).enqueue(new Callback() {
      @Override public void onFailure(@NotNull Call call, @NotNull IOException e) {
      }

      @Override public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
        try (ResponseBody body = response.body()) {
          // Consume and discard the response body.
          body.source().readByteString();
        }
      }
    });
  }

  public static void main(String... args) throws Exception {
    new PrintEvents().run();
  }

  private static final class PrintingEventListener extends EventListener {
    private static final Factory FACTORY = new Factory() {
      final AtomicLong nextCallId = new AtomicLong(1L);

      @NotNull
      @Override public EventListener create(Call call) {
        long callId = nextCallId.getAndIncrement();
        System.out.printf("%04d %s%n", callId, call.request().url());
        return new PrintingEventListener(callId, System.nanoTime());
      }
    };

    final long callId;
    final long callStartNanos;

    PrintingEventListener(long callId, long callStartNanos) {
      this.callId = callId;
      this.callStartNanos = callStartNanos;
    }

    private void printEvent(String name) {
      long elapsedNanos = System.nanoTime() - callStartNanos;
      System.out.printf("%04d %.3f %s%n", callId, elapsedNanos / 1000000000d, name);
    }

    @Override public void proxySelectStart(@NotNull Call call, @NotNull HttpUrl url) {
      printEvent("proxySelectStart");
    }

    @Override public void proxySelectEnd(@NotNull Call call, @NotNull HttpUrl url, @NotNull List<Proxy> proxies) {
      printEvent("proxySelectEnd");
    }

    @Override public void callStart(@NotNull Call call) {
      printEvent("callStart");
    }

    @Override public void dnsStart(@NotNull Call call, @NotNull String domainName) {
      printEvent("dnsStart");
    }

    @Override public void dnsEnd(@NotNull Call call, @NotNull String domainName, @NotNull List<InetAddress> inetAddressList) {
      printEvent("dnsEnd");
    }

    @Override public void connectStart(
        @NotNull Call call, @NotNull InetSocketAddress inetSocketAddress, @NotNull Proxy proxy) {
      printEvent("connectStart");
    }

    @Override public void secureConnectStart(@NotNull Call call) {
      printEvent("secureConnectStart");
    }

    @Override public void secureConnectEnd(@NotNull Call call, Handshake handshake) {
      printEvent("secureConnectEnd");
    }

    @Override public void connectEnd(
        @NotNull Call call, @NotNull InetSocketAddress inetSocketAddress, @NotNull Proxy proxy, Protocol protocol) {
      printEvent("connectEnd");
    }

    @Override public void connectFailed(@NotNull Call call, @NotNull InetSocketAddress inetSocketAddress, @NotNull Proxy proxy,
                                        Protocol protocol, @NotNull IOException ioe) {
      printEvent("connectFailed");
    }

    @Override public void connectionAcquired(@NotNull Call call, @NotNull Connection connection) {
      printEvent("connectionAcquired");
    }

    @Override public void connectionReleased(@NotNull Call call, @NotNull Connection connection) {
      printEvent("connectionReleased");
    }

    @Override public void requestHeadersStart(@NotNull Call call) {
      printEvent("requestHeadersStart");
    }

    @Override public void requestHeadersEnd(@NotNull Call call, @NotNull Request request) {
      printEvent("requestHeadersEnd");
    }

    @Override public void requestBodyStart(@NotNull Call call) {
      printEvent("requestBodyStart");
    }

    @Override public void requestBodyEnd(@NotNull Call call, long byteCount) {
      printEvent("requestBodyEnd");
    }

    @Override public void requestFailed(@NotNull Call call, @NotNull IOException ioe) {
      printEvent("requestFailed");
    }

    @Override public void responseHeadersStart(@NotNull Call call) {
      printEvent("responseHeadersStart");
    }

    @Override public void responseHeadersEnd(@NotNull Call call, @NotNull Response response) {
      printEvent("responseHeadersEnd");
    }

    @Override public void responseBodyStart(@NotNull Call call) {
      printEvent("responseBodyStart");
    }

    @Override public void responseBodyEnd(@NotNull Call call, long byteCount) {
      printEvent("responseBodyEnd");
    }

    @Override public void responseFailed(@NotNull Call call, @NotNull IOException ioe) {
      printEvent("responseFailed");
    }

    @Override public void callEnd(@NotNull Call call) {
      printEvent("callEnd");
    }

    @Override public void callFailed(@NotNull Call call, @NotNull IOException ioe) {
      printEvent("callFailed");
    }

    @Override public void canceled(@NotNull Call call) {
      printEvent("canceled");
    }
  }
}
