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

import okhttp3.HttpUrl;
import okhttp3.internal.SslContextBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;

class UrlConnection extends SynchronousHttpClient {
  private static final boolean VERBOSE = false;

  @Override public void prepare(Benchmark benchmark) {
    super.prepare(benchmark);
    if (benchmark.tls) {
      SSLContext sslContext = SslContextBuilder.localhost();
      SSLSocketFactory socketFactory = sslContext.getSocketFactory();
      HostnameVerifier hostnameVerifier = new HostnameVerifier() {
        @Override public boolean verify(String s, SSLSession session) {
          return true;
        }
      };
      HttpsURLConnection.setDefaultHostnameVerifier(hostnameVerifier);
      HttpsURLConnection.setDefaultSSLSocketFactory(socketFactory);
    }
  }

  @Override public Runnable request(HttpUrl url) {
    return new UrlConnectionRequest(url);
  }

  static class UrlConnectionRequest implements Runnable {
    private final HttpUrl url;

    public UrlConnectionRequest(HttpUrl url) {
      this.url = url;
    }

    public void run() {
      long start = System.nanoTime();
      try {
        HttpURLConnection urlConnection = (HttpURLConnection) url.url().openConnection();
        InputStream in = urlConnection.getInputStream();
        if ("gzip".equals(urlConnection.getHeaderField("Content-Encoding"))) {
          in = new GZIPInputStream(in);
        }

        long total = readAllAndClose(in);
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
