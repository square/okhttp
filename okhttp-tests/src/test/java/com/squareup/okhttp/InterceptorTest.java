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
package com.squareup.okhttp;

import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.RecordedRequest;
import com.squareup.okhttp.mockwebserver.rule.MockWebServerRule;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import okio.Buffer;
import okio.BufferedSink;
import okio.ForwardingSink;
import okio.ForwardingSource;
import okio.GzipSink;
import okio.Okio;
import okio.Sink;
import okio.Source;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public final class InterceptorTest {
  @Rule public MockWebServerRule server = new MockWebServerRule();

  private OkHttpClient client = new OkHttpClient();
  private RecordingCallback callback = new RecordingCallback();

  @Test public void applicationInterceptorsCanShortCircuitResponses() throws Exception {
    server.get().shutdown(); // Accept no connections.

    Request request = new Request.Builder()
        .url("https://localhost:1/")
        .build();

    final Response interceptorResponse = new Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("Intercepted!")
        .body(ResponseBody.create(MediaType.parse("text/plain; charset=utf-8"), "abc"))
        .build();

    client.interceptors().add(new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        return interceptorResponse;
      }
    });

    Response response = client.newCall(request).execute();
    assertSame(interceptorResponse, response);
  }

  @Test public void networkInterceptorsCannotShortCircuitResponses() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(500));

    Interceptor interceptor = new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        return new Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("Intercepted!")
            .body(ResponseBody.create(MediaType.parse("text/plain; charset=utf-8"), "abc"))
            .build();
      }
    };
    client.networkInterceptors().add(interceptor);

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();

    try {
      client.newCall(request).execute();
      fail();
    } catch (IllegalStateException expected) {
      assertEquals("network interceptor " + interceptor + " must call proceed() exactly once",
          expected.getMessage());
    }
  }

  @Test public void networkInterceptorsCannotCallProceedMultipleTimes() throws Exception {
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    Interceptor interceptor = new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        chain.proceed(chain.request());
        return chain.proceed(chain.request());
      }
    };
    client.networkInterceptors().add(interceptor);

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();

    try {
      client.newCall(request).execute();
      fail();
    } catch (IllegalStateException expected) {
      assertEquals("network interceptor " + interceptor + " must call proceed() exactly once",
          expected.getMessage());
    }
  }

  @Test public void networkInterceptorsCannotChangeServerAddress() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(500));

    Interceptor interceptor = new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        Address address = chain.connection().getRoute().getAddress();
        String sameHost = address.getUriHost();
        int differentPort = address.getUriPort() + 1;
        return chain.proceed(chain.request().newBuilder()
            .url(new URL("http://" + sameHost + ":" + differentPort + "/"))
            .build());
      }
    };
    client.networkInterceptors().add(interceptor);

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();

    try {
      client.newCall(request).execute();
      fail();
    } catch (IllegalStateException expected) {
      assertEquals("network interceptor " + interceptor + " must retain the same host and port",
          expected.getMessage());
    }
  }

  @Test public void networkInterceptorsHaveConnectionAccess() throws Exception {
    server.enqueue(new MockResponse());

    client.networkInterceptors().add(new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        Connection connection = chain.connection();
        assertNotNull(connection);
        return chain.proceed(chain.request());
      }
    });

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();
    client.newCall(request).execute();
  }

  @Test public void networkInterceptorsObserveNetworkHeaders() throws Exception {
    server.enqueue(new MockResponse()
        .setBody(gzip("abcabcabc"))
        .addHeader("Content-Encoding: gzip"));

    client.networkInterceptors().add(new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        // The network request has everything: User-Agent, Host, Accept-Encoding.
        Request networkRequest = chain.request();
        assertNotNull(networkRequest.header("User-Agent"));
        assertEquals(server.get().getHostName() + ":" + server.get().getPort(),
            networkRequest.header("Host"));
        assertNotNull(networkRequest.header("Accept-Encoding"));

        // The network response also has everything, including the raw gzipped content.
        Response networkResponse = chain.proceed(networkRequest);
        assertEquals("gzip", networkResponse.header("Content-Encoding"));
        return networkResponse;
      }
    });

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();

    // No extra headers in the application's request.
    assertNull(request.header("User-Agent"));
    assertNull(request.header("Host"));
    assertNull(request.header("Accept-Encoding"));

    // No extra headers in the application's response.
    Response response = client.newCall(request).execute();
    assertNull(request.header("Content-Encoding"));
    assertEquals("abcabcabc", response.body().string());
  }

  @Test public void applicationInterceptorsRewriteRequestToServer() throws Exception {
    rewriteRequestToServer(client.interceptors());
  }

  @Test public void networkInterceptorsRewriteRequestToServer() throws Exception {
    rewriteRequestToServer(client.networkInterceptors());
  }

  private void rewriteRequestToServer(List<Interceptor> interceptors) throws Exception {
    server.enqueue(new MockResponse());

    interceptors.add(new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        Request originalRequest = chain.request();
        return chain.proceed(originalRequest.newBuilder()
            .method("POST", uppercase(originalRequest.body()))
            .addHeader("OkHttp-Intercepted", "yep")
            .build());
      }
    });

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .addHeader("Original-Header", "foo")
        .method("PUT", RequestBody.create(MediaType.parse("text/plain"), "abc"))
        .build();

    client.newCall(request).execute();

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("ABC", recordedRequest.getBody().readUtf8());
    assertEquals("foo", recordedRequest.getHeader("Original-Header"));
    assertEquals("yep", recordedRequest.getHeader("OkHttp-Intercepted"));
    assertEquals("POST", recordedRequest.getMethod());
  }

  @Test public void applicationInterceptorsRewriteResponseFromServer() throws Exception {
    rewriteResponseFromServer(client.interceptors());
  }

  @Test public void networkInterceptorsRewriteResponseFromServer() throws Exception {
    rewriteResponseFromServer(client.networkInterceptors());
  }

  private void rewriteResponseFromServer(List<Interceptor> interceptors) throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Original-Header: foo")
        .setBody("abc"));

    interceptors.add(new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        Response originalResponse = chain.proceed(chain.request());
        return originalResponse.newBuilder()
            .body(uppercase(originalResponse.body()))
            .addHeader("OkHttp-Intercepted", "yep")
            .build();
      }
    });

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();

    Response response = client.newCall(request).execute();
    assertEquals("ABC", response.body().string());
    assertEquals("yep", response.header("OkHttp-Intercepted"));
    assertEquals("foo", response.header("Original-Header"));
  }

  @Test public void multipleApplicationInterceptors() throws Exception {
    multipleInterceptors(client.interceptors());
  }

  @Test public void multipleNetworkInterceptors() throws Exception {
    multipleInterceptors(client.networkInterceptors());
  }

  private void multipleInterceptors(List<Interceptor> interceptors) throws Exception {
    server.enqueue(new MockResponse());

    interceptors.add(new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        Request originalRequest = chain.request();
        Response originalResponse = chain.proceed(originalRequest.newBuilder()
            .addHeader("Request-Interceptor", "Android") // 1. Added first.
            .build());
        return originalResponse.newBuilder()
            .addHeader("Response-Interceptor", "Donut") // 4. Added last.
            .build();
      }
    });
    interceptors.add(new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        Request originalRequest = chain.request();
        Response originalResponse = chain.proceed(originalRequest.newBuilder()
            .addHeader("Request-Interceptor", "Bob") // 2. Added second.
            .build());
        return originalResponse.newBuilder()
            .addHeader("Response-Interceptor", "Cupcake") // 3. Added third.
            .build();
      }
    });

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();

    Response response = client.newCall(request).execute();
    assertEquals(Arrays.asList("Cupcake", "Donut"),
        response.headers("Response-Interceptor"));

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals(Arrays.asList("Android", "Bob"),
        recordedRequest.getHeaders().values("Request-Interceptor"));
  }

  @Test public void asyncApplicationInterceptors() throws Exception {
    asyncInterceptors(client.interceptors());
  }

  @Test public void asyncNetworkInterceptors() throws Exception {
    asyncInterceptors(client.networkInterceptors());
  }

  private void asyncInterceptors(List<Interceptor> interceptors) throws Exception {
    server.enqueue(new MockResponse());

    interceptors.add(new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        Response originalResponse = chain.proceed(chain.request());
        return originalResponse.newBuilder()
            .addHeader("OkHttp-Intercepted", "yep")
            .build();
      }
    });

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();
    client.newCall(request).enqueue(callback);

    callback.await(request.url())
        .assertCode(200)
        .assertHeader("OkHttp-Intercepted", "yep");
  }

  @Test public void applicationInterceptorsCanMakeMultipleRequestsToServer() throws Exception {
    server.enqueue(new MockResponse().setBody("a"));
    server.enqueue(new MockResponse().setBody("b"));

    client.interceptors().add(new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        chain.proceed(chain.request());
        return chain.proceed(chain.request());
      }
    });

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();

    Response response = client.newCall(request).execute();
    assertEquals(response.body().string(), "b");
  }

  /** Make sure interceptors can interact with the OkHttp client. */
  @Test public void interceptorMakesAnUnrelatedRequest() throws Exception {
    server.enqueue(new MockResponse().setBody("a")); // Fetched by interceptor.
    server.enqueue(new MockResponse().setBody("b")); // Fetched directly.

    client.interceptors().add(new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        if (chain.request().url().getPath().equals("/b")) {
          Request requestA = new Request.Builder()
              .url(server.getUrl("/a"))
              .build();
          Response responseA = client.newCall(requestA).execute();
          assertEquals("a", responseA.body().string());
        }

        return chain.proceed(chain.request());
      }
    });

    Request requestB = new Request.Builder()
        .url(server.getUrl("/b"))
        .build();
    Response responseB = client.newCall(requestB).execute();
    assertEquals("b", responseB.body().string());
  }

  /** Make sure interceptors can interact with the OkHttp client asynchronously. */
  @Test public void interceptorMakesAnUnrelatedAsyncRequest() throws Exception {
    server.enqueue(new MockResponse().setBody("a")); // Fetched by interceptor.
    server.enqueue(new MockResponse().setBody("b")); // Fetched directly.

    client.interceptors().add(new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        if (chain.request().url().getPath().equals("/b")) {
          Request requestA = new Request.Builder()
              .url(server.getUrl("/a"))
              .build();

          try {
            RecordingCallback callbackA = new RecordingCallback();
            client.newCall(requestA).enqueue(callbackA);
            callbackA.await(requestA.url()).assertBody("a");
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }

        return chain.proceed(chain.request());
      }
    });

    Request requestB = new Request.Builder()
        .url(server.getUrl("/b"))
        .build();
    RecordingCallback callbackB = new RecordingCallback();
    client.newCall(requestB).enqueue(callbackB);
    callbackB.await(requestB.url()).assertBody("b");
  }

  @Test public void applicationkInterceptorThrowsRuntimeExceptionSynchronous() throws Exception {
    interceptorThrowsRuntimeExceptionSynchronous(client.interceptors());
  }

  @Test public void networkInterceptorThrowsRuntimeExceptionSynchronous() throws Exception {
    interceptorThrowsRuntimeExceptionSynchronous(client.networkInterceptors());
  }

  /**
   * When an interceptor throws an unexpected exception, synchronous callers can catch it and deal
   * with it.
   *
   * TODO(jwilson): test that resources are not leaked when this happens.
   */
  private void interceptorThrowsRuntimeExceptionSynchronous(
      List<Interceptor> interceptors) throws Exception {
    interceptors.add(new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        throw new RuntimeException("boom!");
      }
    });

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();

    try {
      client.newCall(request).execute();
      fail();
    } catch (RuntimeException expected) {
      assertEquals("boom!", expected.getMessage());
    }
  }

  @Test public void applicationInterceptorThrowsRuntimeExceptionAsynchronous() throws Exception {
    interceptorThrowsRuntimeExceptionAsynchronous(client.interceptors());
  }

  @Test public void networkInterceptorThrowsRuntimeExceptionAsynchronous() throws Exception {
    interceptorThrowsRuntimeExceptionAsynchronous(client.networkInterceptors());
  }

  /**
   * When an interceptor throws an unexpected exception, asynchronous callers are left hanging. The
   * exception goes to the uncaught exception handler.
   *
   * TODO(jwilson): test that resources are not leaked when this happens.
   */
  private void interceptorThrowsRuntimeExceptionAsynchronous(
        List<Interceptor> interceptors) throws Exception {
    interceptors.add(new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        throw new RuntimeException("boom!");
      }
    });

    ExceptionCatchingExecutor executor = new ExceptionCatchingExecutor();
    client.setDispatcher(new Dispatcher(executor));

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();
    client.newCall(request).enqueue(callback);

    assertEquals("boom!", executor.takeException().getMessage());
  }

  private RequestBody uppercase(final RequestBody original) {
    return new RequestBody() {
      @Override public MediaType contentType() {
        return original.contentType();
      }

      @Override public long contentLength() throws IOException {
        return original.contentLength();
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        Sink uppercase = uppercase(sink);
        BufferedSink bufferedSink = Okio.buffer(uppercase);
        original.writeTo(bufferedSink);
        bufferedSink.emit();
      }
    };
  }

  private Sink uppercase(final BufferedSink original) {
    return new ForwardingSink(original) {
      @Override public void write(Buffer source, long byteCount) throws IOException {
        original.writeUtf8(source.readUtf8(byteCount).toUpperCase(Locale.US));
      }
    };
  }

  static ResponseBody uppercase(ResponseBody original) throws IOException {
    return ResponseBody.create(original.contentType(), original.contentLength(),
        Okio.buffer(uppercase(original.source())));
  }

  private static Source uppercase(final Source original) {
    return new ForwardingSource(original) {
      @Override public long read(Buffer sink, long byteCount) throws IOException {
        Buffer mixedCase = new Buffer();
        long count = original.read(mixedCase, byteCount);
        sink.writeUtf8(mixedCase.readUtf8().toUpperCase(Locale.US));
        return count;
      }
    };
  }

  private Buffer gzip(String data) throws IOException {
    Buffer result = new Buffer();
    BufferedSink sink = Okio.buffer(new GzipSink(result));
    sink.writeUtf8(data);
    sink.close();
    return result;
  }

  /** Catches exceptions that are otherwise headed for the uncaught exception handler. */
  private static class ExceptionCatchingExecutor extends ThreadPoolExecutor {
    private final BlockingQueue<Exception> exceptions = new LinkedBlockingQueue<>();

    public ExceptionCatchingExecutor() {
      super(1, 1, 0, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
    }

    @Override public void execute(final Runnable runnable) {
      super.execute(new Runnable() {
        @Override public void run() {
          try {
            runnable.run();
          } catch (Exception e) {
            exceptions.add(e);
          }
        }
      });
    }

    public Exception takeException() throws InterruptedException {
      return exceptions.take();
    }
  }
}
