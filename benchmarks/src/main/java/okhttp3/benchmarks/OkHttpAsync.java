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
package okhttp3.benchmarks;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.Body;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.tls.SslClient;

class OkHttpAsync implements HttpClient {
  private static final boolean VERBOSE = false;

  private final AtomicInteger requestsInFlight = new AtomicInteger();

  private OkHttpClient client;
  private Callback callback;
  private int concurrencyLevel;
  private int targetBacklog;

  @Override public void prepare(final Benchmark benchmark) {
    concurrencyLevel = benchmark.concurrencyLevel;
    targetBacklog = benchmark.targetBacklog;

    client = new OkHttpClient.Builder()
        .protocols(benchmark.protocols)
        .dispatcher(new Dispatcher(new ThreadPoolExecutor(benchmark.concurrencyLevel,
            benchmark.concurrencyLevel, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>())))
        .build();

    if (benchmark.tls) {
      SslClient sslClient = SslClient.localhost();
      SSLSocketFactory socketFactory = sslClient.socketFactory;
      HostnameVerifier hostnameVerifier = new HostnameVerifier() {
        @Override public boolean verify(String s, SSLSession session) {
          return true;
        }
      };
      client = client.newBuilder()
          .sslSocketFactory(socketFactory, sslClient.trustManager)
          .hostnameVerifier(hostnameVerifier)
          .build();
    }

    callback = new Callback() {
      @Override public void onFailure(Call call, IOException e) {
        System.out.println("Failed: " + e);
      }

      @Override public void onResponse(Call call, Response response) throws IOException {
        Body body = response.body();
        long total = SynchronousHttpClient.readAllAndClose(body.byteStream());
        long finish = System.nanoTime();
        if (VERBOSE) {
          long start = (Long) response.request().tag();
          System.out.printf("Transferred % 8d bytes in %4d ms%n",
              total, TimeUnit.NANOSECONDS.toMillis(finish - start));
        }
        requestsInFlight.decrementAndGet();
      }
    };
  }

  @Override public void enqueue(HttpUrl url) throws Exception {
    requestsInFlight.incrementAndGet();
    client.newCall(new Request.Builder().tag(System.nanoTime()).url(url).build()).enqueue(callback);
  }

  @Override public synchronized boolean acceptingJobs() {
    return requestsInFlight.get() < (concurrencyLevel + targetBacklog);
  }
}
