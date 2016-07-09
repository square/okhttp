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

import com.google.caliper.Param;
import com.google.caliper.model.ArbitraryMeasurement;
import com.google.caliper.runner.CaliperMain;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import okhttp3.HttpUrl;
import okhttp3.Protocol;
import okhttp3.internal.tls.SslClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import okio.GzipSink;

/**
 * This benchmark is fake, but may be useful for certain relative comparisons. It uses a local
 * connection to a MockWebServer to measure how many identical requests per second can be carried
 * over a fixed number of threads.
 */
public class Benchmark extends com.google.caliper.Benchmark {
  private static final int NUM_REPORTS = 10;
  private static final boolean VERBOSE = false;

  private final Random random = new Random(0);

  /** Which client to run. */
  @Param
  Client client;

  /** How many concurrent requests to execute. */
  @Param({"1", "10"})
  int concurrencyLevel;

  /** How many requests to enqueue to await threads to execute them. */
  @Param({"10"})
  int targetBacklog;

  /** True to use TLS. */
  // TODO: compare different ciphers?
  @Param
  boolean tls;

  /** True to use gzip content-encoding for the response body. */
  @Param
  boolean gzip;

  /** Don't combine chunked with HTTP_2; that's not allowed. */
  @Param
  boolean chunked;

  /** The size of the HTTP response body, in uncompressed bytes. */
  @Param({"128", "1048576"})
  int bodyByteCount;

  /** How many additional headers were included, beyond the built-in ones. */
  @Param({"0", "20"})
  int headerCount;

  /** Which ALPN protocols are in use. Only useful with TLS. */
  List<Protocol> protocols = Arrays.asList(Protocol.HTTP_1_1);

  public static void main(String[] args) {
    List<String> allArgs = new ArrayList<>();
    allArgs.add("--instrument");
    allArgs.add("arbitrary");
    allArgs.addAll(Arrays.asList(args));

    CaliperMain.main(Benchmark.class, allArgs.toArray(new String[allArgs.size()]));
  }

  @ArbitraryMeasurement(description = "requests per second")
  public double run() throws Exception {
    if (VERBOSE) System.out.println(toString());
    HttpClient httpClient = client.create();

    // Prepare the client & server
    httpClient.prepare(this);
    MockWebServer server = startServer();
    HttpUrl url = server.url("/");

    int requestCount = 0;
    long reportStart = System.nanoTime();
    long reportPeriod = TimeUnit.SECONDS.toNanos(1);
    int reports = 0;
    double best = 0.0;

    // Run until we've printed enough reports.
    while (reports < NUM_REPORTS) {
      // Print a report if we haven't recently.
      long now = System.nanoTime();
      double reportDuration = now - reportStart;
      if (reportDuration > reportPeriod) {
        double requestsPerSecond = requestCount / reportDuration * TimeUnit.SECONDS.toNanos(1);
        if (VERBOSE) {
          System.out.println(String.format("Requests per second: %.1f", requestsPerSecond));
        }
        best = Math.max(best, requestsPerSecond);
        requestCount = 0;
        reportStart = now;
        reports++;
      }

      // Fill the job queue with work.
      while (httpClient.acceptingJobs()) {
        httpClient.enqueue(url);
        requestCount++;
      }

      // The job queue is full. Take a break.
      sleep(1);
    }

    return best;
  }

  @Override public String toString() {
    List<Object> modifiers = new ArrayList<>();
    if (tls) modifiers.add("tls");
    if (gzip) modifiers.add("gzip");
    if (chunked) modifiers.add("chunked");
    modifiers.addAll(protocols);

    return String.format("%s %s\nbodyByteCount=%s headerCount=%s concurrencyLevel=%s",
        client, modifiers, bodyByteCount, headerCount, concurrencyLevel);
  }

  private void sleep(int millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ignored) {
    }
  }

  private MockWebServer startServer() throws IOException {
    Logger.getLogger(MockWebServer.class.getName()).setLevel(Level.WARNING);
    MockWebServer server = new MockWebServer();

    if (tls) {
      SslClient sslClient = SslClient.localhost();
      server.useHttps(sslClient.socketFactory, false);
      server.setProtocols(protocols);
    }

    final MockResponse response = newResponse();
    server.setDispatcher(new Dispatcher() {
      @Override public MockResponse dispatch(RecordedRequest request) {
        return response;
      }
    });

    server.start();
    return server;
  }

  private MockResponse newResponse() throws IOException {
    byte[] bytes = new byte[bodyByteCount];
    random.nextBytes(bytes);
    Buffer body = new Buffer().write(bytes);

    MockResponse result = new MockResponse();

    if (gzip) {
      Buffer gzipBody = new Buffer();
      GzipSink gzipSink = new GzipSink(gzipBody);
      gzipSink.write(body, body.size());
      gzipSink.close();
      body = gzipBody;
      result.addHeader("Content-Encoding: gzip");
    }

    if (chunked) {
      result.setChunkedBody(body, 1024);
    } else {
      result.setBody(body);
    }

    for (int i = 0; i < headerCount; i++) {
      result.addHeader(randomString(12), randomString(20));
    }

    return result;
  }

  private String randomString(int length) {
    String alphabet = "-abcdefghijklmnopqrstuvwxyz";
    char[] result = new char[length];
    for (int i = 0; i < length; i++) {
      result[i] = alphabet.charAt(random.nextInt(alphabet.length()));
    }
    return new String(result);
  }
}
