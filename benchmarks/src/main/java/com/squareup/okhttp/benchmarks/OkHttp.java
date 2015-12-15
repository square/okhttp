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
package com.squareup.okhttp.benchmarks;

import com.squareup.okhttp.Call;
import com.squareup.okhttp.HttpUrl;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.ResponseBody;
import com.squareup.okhttp.internal.SslContextBuilder;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;

class OkHttp extends SynchronousHttpClient {
  private static final boolean VERBOSE = false;

  private OkHttpClient client;

  @Override public void prepare(Benchmark benchmark) {
    super.prepare(benchmark);
    client = new OkHttpClient();
    client.setProtocols(benchmark.protocols);

    if (benchmark.tls) {
      SSLContext sslContext = SslContextBuilder.localhost();
      SSLSocketFactory socketFactory = sslContext.getSocketFactory();
      HostnameVerifier hostnameVerifier = new HostnameVerifier() {
        @Override public boolean verify(String s, SSLSession session) {
          return true;
        }
      };
      client.setSslSocketFactory(socketFactory);
      client.setHostnameVerifier(hostnameVerifier);
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
        ResponseBody body = call.execute().body();
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
