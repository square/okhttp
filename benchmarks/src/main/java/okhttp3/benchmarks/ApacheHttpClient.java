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
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import okhttp3.HttpUrl;
import okhttp3.internal.tls.SslClient;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.PoolingClientConnectionManager;

/** Benchmark Apache HTTP client. */
class ApacheHttpClient extends SynchronousHttpClient {
  private static final boolean VERBOSE = false;

  private HttpClient client;

  @Override public void prepare(Benchmark benchmark) {
    super.prepare(benchmark);
    ClientConnectionManager connectionManager = new PoolingClientConnectionManager();
    if (benchmark.tls) {
      SslClient sslClient = SslClient.localhost();
      connectionManager.getSchemeRegistry().register(
          new Scheme("https", 443, new SSLSocketFactory(sslClient.sslContext)));
    }
    client = new DefaultHttpClient(connectionManager);
  }

  @Override public Runnable request(HttpUrl url) {
    return new ApacheHttpClientRequest(url);
  }

  class ApacheHttpClientRequest implements Runnable {
    private final HttpUrl url;

    public ApacheHttpClientRequest(HttpUrl url) {
      this.url = url;
    }

    public void run() {
      long start = System.nanoTime();
      try {
        HttpResponse response = client.execute(new HttpGet(url.toString()));
        InputStream in = response.getEntity().getContent();
        Header contentEncoding = response.getFirstHeader("Content-Encoding");
        if (contentEncoding != null && contentEncoding.getValue().equals("gzip")) {
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
