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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import okhttp3.HttpUrl;
import okhttp3.Protocol;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okio.Buffer;
import okio.GzipSink;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import static okhttp3.tls.internal.TlsUtil.localhost;

/**
 * This benchmark is fake, but may be useful for certain relative comparisons. It uses a local
 * connection to a MockWebServer to measure how many identical requests per second can be carried
 * over a fixed number of threads.
 */

@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 10, time = 100, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 100, timeUnit = TimeUnit.MILLISECONDS)
@BenchmarkMode({Mode.Throughput})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class Benchmark {

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
  @Param({"false", "true"})
  boolean tls;

  /** True to use gzip content-encoding for the response body. */
  @Param({"false", "true"})
  boolean gzip;

  /** Don't combine chunked with HTTP_2; that's not allowed. */
  @Param({"false", "true"})
  boolean chunked;

  /** The size of the HTTP response body, in uncompressed bytes. */
  @Param({"128", "1048576"})
  int bodyByteCount;

  /** How many additional headers were included, beyond the built-in ones. */
  @Param({"0", "20"})
  int headerCount;

  HttpClient httpClient;
  MockWebServer server;
  HttpUrl url;

  /** Which ALPN protocols are in use. Only useful with TLS. */
  List<Protocol> protocols = Arrays.asList(Protocol.HTTP_1_1);

  public static void main(String[] args) throws Exception {
    Options opt = new OptionsBuilder()
            .include(Benchmark.class.getSimpleName())
            .build();

    new Runner(opt).run();
  }

  @Setup(org.openjdk.jmh.annotations.Level.Trial)
  public void init() throws Exception {
    httpClient = client.create();
    httpClient.prepare(this);
    server = startServer();
    url = server.url("/");
  }

  @TearDown(org.openjdk.jmh.annotations.Level.Trial)
  public void dispose() throws Exception {
    //Waiting for consuming
    Thread.sleep(500);

    httpClient.cleanUp();
    server.shutdown();
  }

  @org.openjdk.jmh.annotations.Benchmark
  public void run() throws Exception {
    // The job queue is full. Take a break.
    while (!httpClient.acceptingJobs()) {
      sleep(1);
    }

    // Fill the job queue with work.
    httpClient.enqueue(url);
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
    MockWebServer server = new MockWebServer();
    Logger.getLogger(MockWebServer.class.getName()).setLevel(Level.WARNING);

    if (tls) {
      HandshakeCertificates handshakeCertificates = localhost();
      server.useHttps(handshakeCertificates.sslSocketFactory(), false);
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
