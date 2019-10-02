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
package okhttp3;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class InterceptorTest {
  @Rule public MockWebServer server = new MockWebServer();
  @Rule public final OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();

  private OkHttpClient client = clientTestRule.newClient();
  private RecordingCallback callback = new RecordingCallback();

  @Test public void applicationInterceptorsCanShortCircuitResponses() throws Exception {
    server.shutdown(); // Accept no connections.

    Request request = new Request.Builder()
        .url("https://localhost:1/")
        .build();

    Response interceptorResponse = new Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("Intercepted!")
        .body(ResponseBody.create("abc", MediaType.get("text/plain; charset=utf-8")))
        .build();

    client = client.newBuilder()
        .addInterceptor(chain -> interceptorResponse)
        .build();

    Response response = client.newCall(request).execute();
    assertThat(response).isSameAs(interceptorResponse);
  }

  @Test public void networkInterceptorsCannotShortCircuitResponses() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(500));

    Interceptor interceptor = chain -> new Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("Intercepted!")
        .body(ResponseBody.create("abc", MediaType.get("text/plain; charset=utf-8")))
        .build();
    client = client.newBuilder()
        .addNetworkInterceptor(interceptor)
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();

    try {
      client.newCall(request).execute();
      fail();
    } catch (IllegalStateException expected) {
      assertThat(expected.getMessage()).isEqualTo(
          ("network interceptor " + interceptor + " must call proceed() exactly once"));
    }
  }

  @Test public void networkInterceptorsCannotCallProceedMultipleTimes() throws Exception {
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    Interceptor interceptor = chain -> {
      chain.proceed(chain.request());
      return chain.proceed(chain.request());
    };
    client = client.newBuilder()
        .addNetworkInterceptor(interceptor)
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();

    try {
      client.newCall(request).execute();
      fail();
    } catch (IllegalStateException expected) {
      assertThat(expected.getMessage()).isEqualTo(
          ("network interceptor " + interceptor + " must call proceed() exactly once"));
    }
  }

  @Test public void networkInterceptorsCannotChangeServerAddress() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(500));

    Interceptor interceptor = chain -> {
      Address address = chain.connection().route().address();
      String sameHost = address.url().host();
      int differentPort = address.url().port() + 1;
      return chain.proceed(chain.request().newBuilder()
          .url("http://" + sameHost + ":" + differentPort + "/")
          .build());
    };
    client = client.newBuilder()
        .addNetworkInterceptor(interceptor)
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();

    try {
      client.newCall(request).execute();
      fail();
    } catch (IllegalStateException expected) {
      assertThat(expected.getMessage()).isEqualTo(
          ("network interceptor " + interceptor + " must retain the same host and port"));
    }
  }

  @Test public void networkInterceptorsHaveConnectionAccess() throws Exception {
    server.enqueue(new MockResponse());

    Interceptor interceptor = chain -> {
      Connection connection = chain.connection();
      assertThat(connection).isNotNull();
      return chain.proceed(chain.request());
    };
    client = client.newBuilder()
        .addNetworkInterceptor(interceptor)
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    client.newCall(request).execute();
  }

  @Test public void networkInterceptorsObserveNetworkHeaders() throws Exception {
    server.enqueue(new MockResponse()
        .setBody(gzip("abcabcabc"))
        .addHeader("Content-Encoding: gzip"));

    Interceptor interceptor = chain -> {
      // The network request has everything: User-Agent, Host, Accept-Encoding.
      Request networkRequest = chain.request();
      assertThat(networkRequest.header("User-Agent")).isNotNull();
      assertThat(networkRequest.header("Host")).isEqualTo(
          (server.getHostName() + ":" + server.getPort()));
      assertThat(networkRequest.header("Accept-Encoding")).isNotNull();

      // The network response also has everything, including the raw gzipped content.
      Response networkResponse = chain.proceed(networkRequest);
      assertThat(networkResponse.header("Content-Encoding")).isEqualTo("gzip");
      return networkResponse;
    };
    client = client.newBuilder()
        .addNetworkInterceptor(interceptor)
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();

    // No extra headers in the application's request.
    assertThat(request.header("User-Agent")).isNull();
    assertThat(request.header("Host")).isNull();
    assertThat(request.header("Accept-Encoding")).isNull();

    // No extra headers in the application's response.
    Response response = client.newCall(request).execute();
    assertThat(request.header("Content-Encoding")).isNull();
    assertThat(response.body().string()).isEqualTo("abcabcabc");
  }

  @Test public void networkInterceptorsCanChangeRequestMethodFromGetToPost() throws Exception {
    server.enqueue(new MockResponse());

    Interceptor interceptor = chain -> {
      Request originalRequest = chain.request();
      MediaType mediaType = MediaType.get("text/plain");
      RequestBody body = RequestBody.create("abc", mediaType);
      return chain.proceed(originalRequest.newBuilder()
          .method("POST", body)
          .header("Content-Type", mediaType.toString())
          .header("Content-Length", Long.toString(body.contentLength()))
          .build());
    };
    client = client.newBuilder()
        .addNetworkInterceptor(interceptor)
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .get()
        .build();

    client.newCall(request).execute();

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("POST");
    assertThat(recordedRequest.getBody().readUtf8()).isEqualTo("abc");
  }

  @Test public void applicationInterceptorsRewriteRequestToServer() throws Exception {
    rewriteRequestToServer(false);
  }

  @Test public void networkInterceptorsRewriteRequestToServer() throws Exception {
    rewriteRequestToServer(true);
  }

  private void rewriteRequestToServer(boolean network) throws Exception {
    server.enqueue(new MockResponse());

    addInterceptor(network, chain -> {
      Request originalRequest = chain.request();
      return chain.proceed(originalRequest.newBuilder()
          .method("POST", uppercase(originalRequest.body()))
          .addHeader("OkHttp-Intercepted", "yep")
          .build());
    });

    Request request = new Request.Builder()
        .url(server.url("/"))
        .addHeader("Original-Header", "foo")
        .method("PUT", RequestBody.create("abc", MediaType.get("text/plain")))
        .build();

    client.newCall(request).execute();

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getBody().readUtf8()).isEqualTo("ABC");
    assertThat(recordedRequest.getHeader("Original-Header")).isEqualTo("foo");
    assertThat(recordedRequest.getHeader("OkHttp-Intercepted")).isEqualTo("yep");
    assertThat(recordedRequest.getMethod()).isEqualTo("POST");
  }

  @Test public void applicationInterceptorsRewriteResponseFromServer() throws Exception {
    rewriteResponseFromServer(false);
  }

  @Test public void networkInterceptorsRewriteResponseFromServer() throws Exception {
    rewriteResponseFromServer(true);
  }

  private void rewriteResponseFromServer(boolean network) throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Original-Header: foo")
        .setBody("abc"));

    addInterceptor(network, chain -> {
      Response originalResponse = chain.proceed(chain.request());
      return originalResponse.newBuilder()
          .body(uppercase(originalResponse.body()))
          .addHeader("OkHttp-Intercepted", "yep")
          .build();
    });

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();

    Response response = client.newCall(request).execute();
    assertThat(response.body().string()).isEqualTo("ABC");
    assertThat(response.header("OkHttp-Intercepted")).isEqualTo("yep");
    assertThat(response.header("Original-Header")).isEqualTo("foo");
  }

  @Test public void multipleApplicationInterceptors() throws Exception {
    multipleInterceptors(false);
  }

  @Test public void multipleNetworkInterceptors() throws Exception {
    multipleInterceptors(true);
  }

  private void multipleInterceptors(boolean network) throws Exception {
    server.enqueue(new MockResponse());

    addInterceptor(network, chain -> {
      Request originalRequest = chain.request();
      Response originalResponse = chain.proceed(originalRequest.newBuilder()
          .addHeader("Request-Interceptor", "Android") // 1. Added first.
          .build());
      return originalResponse.newBuilder()
          .addHeader("Response-Interceptor", "Donut") // 4. Added last.
          .build();
    });
    addInterceptor(network, chain -> {
      Request originalRequest = chain.request();
      Response originalResponse = chain.proceed(originalRequest.newBuilder()
          .addHeader("Request-Interceptor", "Bob") // 2. Added second.
          .build());
      return originalResponse.newBuilder()
          .addHeader("Response-Interceptor", "Cupcake") // 3. Added third.
          .build();
    });

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();

    Response response = client.newCall(request).execute();
    assertThat(response.headers("Response-Interceptor")).containsExactly("Cupcake", "Donut");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getHeaders().values("Request-Interceptor"))
        .containsExactly("Android", "Bob");
  }

  @Test public void asyncApplicationInterceptors() throws Exception {
    asyncInterceptors(false);
  }

  @Test public void asyncNetworkInterceptors() throws Exception {
    asyncInterceptors(true);
  }

  private void asyncInterceptors(boolean network) throws Exception {
    server.enqueue(new MockResponse());

    addInterceptor(network, chain -> {
      Response originalResponse = chain.proceed(chain.request());
      return originalResponse.newBuilder()
          .addHeader("OkHttp-Intercepted", "yep")
          .build();
    });

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    client.newCall(request).enqueue(callback);

    callback.await(request.url())
        .assertCode(200)
        .assertHeader("OkHttp-Intercepted", "yep");
  }

  @Test public void applicationInterceptorsCanMakeMultipleRequestsToServer() throws Exception {
    server.enqueue(new MockResponse().setBody("a"));
    server.enqueue(new MockResponse().setBody("b"));

    client = client.newBuilder()
        .addInterceptor(chain -> {
          Response response1 = chain.proceed(chain.request());
          response1.body().close();
          return chain.proceed(chain.request());
        })
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();

    Response response = client.newCall(request).execute();
    assertThat("b").isEqualTo(response.body().string());
  }

  /** Make sure interceptors can interact with the OkHttp client. */
  @Test public void interceptorMakesAnUnrelatedRequest() throws Exception {
    server.enqueue(new MockResponse().setBody("a")); // Fetched by interceptor.
    server.enqueue(new MockResponse().setBody("b")); // Fetched directly.

    client = client.newBuilder()
        .addInterceptor(chain -> {
          if (chain.request().url().encodedPath().equals("/b")) {
            Request requestA = new Request.Builder()
                .url(server.url("/a"))
                .build();
            Response responseA = client.newCall(requestA).execute();
            assertThat(responseA.body().string()).isEqualTo("a");
          }

          return chain.proceed(chain.request());
        })
        .build();

    Request requestB = new Request.Builder()
        .url(server.url("/b"))
        .build();
    Response responseB = client.newCall(requestB).execute();
    assertThat(responseB.body().string()).isEqualTo("b");
  }

  /** Make sure interceptors can interact with the OkHttp client asynchronously. */
  @Test public void interceptorMakesAnUnrelatedAsyncRequest() throws Exception {
    server.enqueue(new MockResponse().setBody("a")); // Fetched by interceptor.
    server.enqueue(new MockResponse().setBody("b")); // Fetched directly.

    client = client.newBuilder()
        .addInterceptor(chain -> {
          if (chain.request().url().encodedPath().equals("/b")) {
            Request requestA = new Request.Builder()
                .url(server.url("/a"))
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
        })
        .build();

    Request requestB = new Request.Builder()
        .url(server.url("/b"))
        .build();
    RecordingCallback callbackB = new RecordingCallback();
    client.newCall(requestB).enqueue(callbackB);
    callbackB.await(requestB.url()).assertBody("b");
  }

  @Test public void applicationInterceptorThrowsRuntimeExceptionSynchronous() throws Exception {
    interceptorThrowsRuntimeExceptionSynchronous(false);
  }

  @Test public void networkInterceptorThrowsRuntimeExceptionSynchronous() throws Exception {
    interceptorThrowsRuntimeExceptionSynchronous(true);
  }

  /**
   * When an interceptor throws an unexpected exception, synchronous callers can catch it and deal
   * with it.
   */
  private void interceptorThrowsRuntimeExceptionSynchronous(boolean network) throws Exception {
    addInterceptor(network, chain -> { throw new RuntimeException("boom!"); });

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();

    try {
      client.newCall(request).execute();
      fail();
    } catch (RuntimeException expected) {
      assertThat(expected.getMessage()).isEqualTo("boom!");
    }
  }

  @Test public void networkInterceptorModifiedRequestIsReturned() throws IOException {
    server.enqueue(new MockResponse());

    Interceptor modifyHeaderInterceptor = chain -> {
      Request modifiedRequest = chain.request()
          .newBuilder()
          .header("User-Agent", "intercepted request")
          .build();
      return chain.proceed(modifiedRequest);
    };

    client = client.newBuilder()
        .addNetworkInterceptor(modifyHeaderInterceptor)
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("User-Agent", "user request")
        .build();

    Response response = client.newCall(request).execute();
    assertThat(response.request().header("User-Agent")).isNotNull();
    assertThat(response.request().header("User-Agent")).isEqualTo("user request");
    assertThat(response.networkResponse().request().header("User-Agent")).isEqualTo(
        "intercepted request");
  }

  @Test public void applicationInterceptorThrowsRuntimeExceptionAsynchronous() throws Exception {
    interceptorThrowsRuntimeExceptionAsynchronous(false);
  }

  @Test public void networkInterceptorThrowsRuntimeExceptionAsynchronous() throws Exception {
    interceptorThrowsRuntimeExceptionAsynchronous(true);
  }

  /**
   * When an interceptor throws an unexpected exception, asynchronous calls are canceled. The
   * exception goes to the uncaught exception handler.
   */
  private void interceptorThrowsRuntimeExceptionAsynchronous(boolean network) throws Exception {
    RuntimeException boom = new RuntimeException("boom!");
    addInterceptor(network, chain -> { throw boom; });

    ExceptionCatchingExecutor executor = new ExceptionCatchingExecutor();
    client = client.newBuilder()
        .dispatcher(new Dispatcher(executor))
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    Call call = client.newCall(request);
    call.enqueue(callback);
    RecordedResponse recordedResponse = callback.await(server.url("/"));
    assertThat(recordedResponse.failure)
        .hasMessage("canceled due to java.lang.RuntimeException: boom!");
    assertThat(recordedResponse.failure).hasSuppressedException(boom);
    assertThat(call.isCanceled()).isTrue();

    assertThat(executor.takeException()).isEqualTo(boom);
  }

  @Test public void applicationInterceptorReturnsNull() throws Exception {
    server.enqueue(new MockResponse());

    Interceptor interceptor = chain -> {
      chain.proceed(chain.request());
      return null;
    };
    client = client.newBuilder()
        .addInterceptor(interceptor)
        .build();

    ExceptionCatchingExecutor executor = new ExceptionCatchingExecutor();
    client = client.newBuilder()
        .dispatcher(new Dispatcher(executor))
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    try {
      client.newCall(request).execute();
      fail();
    } catch (NullPointerException expected) {
      assertThat(expected.getMessage()).isEqualTo(
          ("interceptor " + interceptor + " returned null"));
    }
  }

  @Test public void networkInterceptorReturnsNull() throws Exception {
    server.enqueue(new MockResponse());

    Interceptor interceptor = chain -> {
      chain.proceed(chain.request());
      return null;
    };
    client = client.newBuilder()
        .addNetworkInterceptor(interceptor)
        .build();

    ExceptionCatchingExecutor executor = new ExceptionCatchingExecutor();
    client = client.newBuilder()
        .dispatcher(new Dispatcher(executor))
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    try {
      client.newCall(request).execute();
      fail();
    } catch (NullPointerException expected) {
      expected.printStackTrace();
      assertThat(expected.getMessage()).isEqualTo(
          ("interceptor " + interceptor + " returned null"));
    }
  }

  @Test public void networkInterceptorReturnsConnectionOnEmptyBody() throws Exception {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .addHeader("Connection", "Close"));

    Interceptor interceptor = chain -> {
      Response response = chain.proceed(chain.request());
      assertThat(chain.connection()).isNotNull();
      return response;
    };

    client = client.newBuilder()
        .addNetworkInterceptor(interceptor)
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();

    Response response = client.newCall(request).execute();
    response.body().close();
  }

  @Test public void applicationInterceptorResponseMustHaveBody() throws Exception {
    server.enqueue(new MockResponse());

    Interceptor interceptor = chain -> {
      Response response = chain.proceed(chain.request());
      return response.newBuilder()
          .body(null)
          .build();
    };
    client = client.newBuilder()
        .addInterceptor(interceptor)
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    try {
      client.newCall(request).execute();
      fail();
    } catch (IllegalStateException expected) {
      assertThat(expected.getMessage()).isEqualTo(
          ("interceptor " + interceptor + " returned a response with no body"));
    }
  }

  @Test public void networkInterceptorResponseMustHaveBody() throws Exception {
    server.enqueue(new MockResponse());

    Interceptor interceptor = chain -> {
      Response response = chain.proceed(chain.request());
      return response.newBuilder()
          .body(null)
          .build();
    };
    client = client.newBuilder()
        .addNetworkInterceptor(interceptor)
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    try {
      client.newCall(request).execute();
      fail();
    } catch (IllegalStateException expected) {
      assertThat(expected.getMessage()).isEqualTo(
          ("interceptor " + interceptor + " returned a response with no body"));
    }
  }

  @Test public void connectTimeout() throws Exception {
    Interceptor interceptor1 = chainA -> {
      assertThat(chainA.connectTimeoutMillis()).isEqualTo(5000);

      Interceptor.Chain chainB = chainA.withConnectTimeout(100, TimeUnit.MILLISECONDS);
      assertThat(chainB.connectTimeoutMillis()).isEqualTo(100);

      return chainB.proceed(chainA.request());
    };

    Interceptor interceptor2 = chain -> {
      assertThat(chain.connectTimeoutMillis()).isEqualTo(100);
      return chain.proceed(chain.request());
    };

    InetAddress localhost = InetAddress.getLoopbackAddress();
    ServerSocket serverSocket = new ServerSocket(0, 1, localhost);
    // Fill backlog queue with this request so subsequent requests will be blocked.
    new Socket().connect(serverSocket.getLocalSocketAddress());

    client = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .addInterceptor(interceptor1)
        .addInterceptor(interceptor2)
        .build();

    Request request1 =
        new Request.Builder()
            .url(
                "http://"
                    + serverSocket.getInetAddress().getCanonicalHostName()
                    + ":"
                    + serverSocket.getLocalPort())
            .build();
    Call call = client.newCall(request1);

    try {
      call.execute();
      fail();
    } catch (SocketTimeoutException expected) {
    }

    serverSocket.close();
  }

  @Test public void chainWithReadTimeout() throws Exception {
    Interceptor interceptor1 = chainA -> {
      assertThat(chainA.readTimeoutMillis()).isEqualTo(5000);

      Interceptor.Chain chainB = chainA.withReadTimeout(100, TimeUnit.MILLISECONDS);
      assertThat(chainB.readTimeoutMillis()).isEqualTo(100);

      return chainB.proceed(chainA.request());
    };

    Interceptor interceptor2 = chain -> {
      assertThat(chain.readTimeoutMillis()).isEqualTo(100);
      return chain.proceed(chain.request());
    };

    client = client.newBuilder()
        .readTimeout(5, TimeUnit.SECONDS)
        .addInterceptor(interceptor1)
        .addInterceptor(interceptor2)
        .build();

    server.enqueue(new MockResponse()
        .setBody("abc")
        .throttleBody(1, 1, TimeUnit.SECONDS));

    Request request1 = new Request.Builder()
        .url(server.url("/"))
        .build();
    Call call = client.newCall(request1);
    Response response = call.execute();
    ResponseBody body = response.body();
    try {
      body.string();
      fail();
    } catch (SocketTimeoutException expected) {
    }
  }

  @Test public void chainWithWriteTimeout() throws Exception {
    Interceptor interceptor1 = chainA -> {
      assertThat(chainA.writeTimeoutMillis()).isEqualTo(5000);

      Interceptor.Chain chainB = chainA.withWriteTimeout(100, TimeUnit.MILLISECONDS);
      assertThat(chainB.writeTimeoutMillis()).isEqualTo(100);

      return chainB.proceed(chainA.request());
    };

    Interceptor interceptor2 = chain -> {
      assertThat(chain.writeTimeoutMillis()).isEqualTo(100);
      return chain.proceed(chain.request());
    };

    client = client.newBuilder()
        .writeTimeout(5, TimeUnit.SECONDS)
        .addInterceptor(interceptor1)
        .addInterceptor(interceptor2)
        .build();

    server.enqueue(new MockResponse()
        .setBody("abc")
        .throttleBody(1, 1, TimeUnit.SECONDS));

    byte[] data = new byte[2 * 1024 * 1024]; // 2 MiB.
    Request request1 = new Request.Builder()
        .url(server.url("/"))
        .post(RequestBody.create(data, MediaType.get("text/plain")))
        .build();
    Call call = client.newCall(request1);

    try {
      call.execute(); // we want this call to throw a SocketTimeoutException
      fail();
    } catch (SocketTimeoutException expected) {
    }
  }

  @Test public void chainCanCancelCall() throws Exception {
    AtomicReference<Call> callRef = new AtomicReference<>();

    Interceptor interceptor = chain -> {
      Call call = chain.call();
      callRef.set(call);

      assertThat(call.isCanceled()).isFalse();
      call.cancel();
      assertThat(call.isCanceled()).isTrue();

      return chain.proceed(chain.request());
    };

    client = client.newBuilder()
        .addInterceptor(interceptor)
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    Call call = client.newCall(request);

    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }

    assertThat(callRef.get()).isSameAs(call);
  }

  private RequestBody uppercase(RequestBody original) {
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

  private Sink uppercase(BufferedSink original) {
    return new ForwardingSink(original) {
      @Override public void write(Buffer source, long byteCount) throws IOException {
        original.writeUtf8(source.readUtf8(byteCount).toUpperCase(Locale.US));
      }
    };
  }

  static ResponseBody uppercase(ResponseBody original) throws IOException {
    return ResponseBody.create(Okio.buffer(uppercase(original.source())),
        original.contentType(), original.contentLength());
  }

  private static Source uppercase(Source original) {
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

  private void addInterceptor(boolean network, Interceptor interceptor) {
    OkHttpClient.Builder builder = client.newBuilder();
    if (network) {
      builder.addNetworkInterceptor(interceptor);
    } else {
      builder.addInterceptor(interceptor);
    }
    client = builder.build();
  }

  /** Catches exceptions that are otherwise headed for the uncaught exception handler. */
  private static class ExceptionCatchingExecutor extends ThreadPoolExecutor {
    private final BlockingQueue<Exception> exceptions = new LinkedBlockingQueue<>();

    public ExceptionCatchingExecutor() {
      super(1, 1, 0, TimeUnit.SECONDS, new SynchronousQueue<>());
    }

    @Override public void execute(Runnable runnable) {
      super.execute(() -> {
        try {
          runnable.run();
        } catch (Exception e) {
          exceptions.add(e);
        }
      });
    }

    public Exception takeException() throws Exception {
      return exceptions.take();
    }
  }
}
