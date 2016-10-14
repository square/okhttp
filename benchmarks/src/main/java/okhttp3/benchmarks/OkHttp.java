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
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Body;
import okhttp3.internal.tls.SslClient;

class OkHttp extends SynchronousHttpClient {
  private static final boolean VERBOSE = false;

  private OkHttpClient client;

  @Override public void prepare(Benchmark benchmark) {
    super.prepare(benchmark);
    client = new OkHttpClient.Builder()
        .protocols(benchmark.protocols)
        .build();

    if (benchmark.tls) {
      SslClient sslClient = SslClient.localhost();
      SSLSocketFactory socketFactory = sslClient.socketFactory;
      HostnameVerifier hostnameVerifier = new HostnameVerifier() {
        @Override public boolean verify(String s, SSLSession session) {
          return true;
        }
      };
      client = new OkHttpClient.Builder()
          .sslSocketFactory(socketFactory, sslClient.trustManager)
          .hostnameVerifier(hostnameVerifier)
          .build();
    }
  }

  @Override public Runnable request(HttpUrl url) {
    Call call = client.newCall(new Request.Builder().url(url).build());
    return new OkHttpRequest(call);
  }

  class OkHttpRequest implements Runnable {
    private final Call call;

    public OkHttpRequest(Call call) {
      this.call = call;
    }

    public void run() {
      long start = System.nanoTime();
      try {
        Body body = call.execute().body();
        long total = readAllAndClose(body.byteStream());
        long finish = System.nanoTime();

        if (VERBOSE) {
          System.out.println(String.format("Transferred % 8d bytes in %4d ms",
              total, TimeUnit.NANOSECONDS.toMillis(finish - start)));
        }
      } catch (IOException e) {
        System.out.println("Failed: " + e);
      }
    }
  }
}
