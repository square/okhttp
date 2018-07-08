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
import java.net.SocketTimeoutException;
import java.util.Arrays;
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

import static okhttp3.TestUtil.defaultClient;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class InterceptorTest {
  @Rule public MockWebServer server = new MockWebServer();

  private OkHttpClient client = defaultClient();
  private RecordingCallback callback = new RecordingCallback();

  @Test public void applicationInterceptorsCanShortCircuitResponses() throws Exception {
    server.shutdown(); // Accept no connections.

    Request request = new Request.Builder()
        .url("https://localhost:1/")
        .build();

    final Response interceptorResponse = new Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("Intercepted!")
        .body(ResponseBody.create(MediaType.get("text/plain; charset=utf-8"), "abc"))
        .build();

    client = client.newBuilder()
        .addInterceptor(new Interceptor() {
          @Override public Response intercept(Chain chain) throws IOException {
            return interceptorResponse;
          }
        }).build();

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
            .body(ResponseBody.create(MediaType.get("text/plain; charset=utf-8"), "abc"))
            .build();
      }
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
      assertEquals("network interceptor " + interceptor + " must call proceed() exactly once",
          expected.getMessage());
    }
  }

  @Test public void networkInterceptorsCannotChangeServerAddress() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(500));

    Interceptor interceptor = new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        Address address = chain.connection().route().address();
        String sameHost = address.url().host();
        int differentPort = address.url().port() + 1;
        return chain.proceed(chain.request().newBuilder()
            .url("http://" + sameHost + ":" + differentPort + "/")
            .build());
      }
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
      assertEquals("network interceptor " + interceptor + " must retain the same host and port",
          expected.getMessage());
    }
  }

  @Test public void networkInterceptorsHaveConnectionAccess() throws Exception {
    server.enqueue(new MockResponse());

    Interceptor interceptor = new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        Connection connection = chain.connection();
        assertNotNull(connection);
        return chain.proceed(chain.request());
      }
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

    Interceptor interceptor = new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        // The network request has everything: User-Agent, Host, Accept-Encoding.
        Request networkRequest = chain.request();
        assertNotNull(networkRequest.header("User-Agent"));
        assertEquals(server.getHostName() + ":" + server.getPort(),
            networkRequest.header("Host"));
        assertNotNull(networkRequest.header("Accept-Encoding"));

        // The network response also has everything, including the raw gzipped content.
        Response networkResponse = chain.proceed(networkRequest);
        assertEquals("gzip", networkResponse.header("Content-Encoding"));
        return networkResponse;
      }
    };
    client = client.newBuilder()
        .addNetworkInterceptor(interceptor)
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
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

  @Test public void networkInterceptorsCanChangeRequestMethodFromGetToPost() throws Exception {
    server.enqueue(new MockResponse());

    Interceptor interceptor = new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        Request originalRequest = chain.request();
        MediaType mediaType = MediaType.get("text/plain");
        RequestBody body = RequestBody.create(mediaType, "abc");
        return chain.proceed(originalRequest.newBuilder()
            .method("POST", body)
            .header("Content-Type", mediaType.toString())
            .header("Content-Length", Long.toString(body.contentLength()))
            .build());
      }
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
    assertEquals("POST", recordedRequest.getMethod());
    assertEquals("abc", recordedRequest.getBody().readUtf8());
  }

  @Test public void applicationInterceptorsRewriteRequestToServer() throws Exception {
    rewriteRequestToServer(false);
  }

  @Test public void networkInterceptorsRewriteRequestToServer() throws Exception {
    rewriteRequestToServer(true);
  }

  private void rewriteRequestToServer(boolean network) throws Exception {
    server.enqueue(new MockResponse());

    addInterceptor(network, new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        Request originalRequest = chain.request();
        return chain.proceed(originalRequest.newBuilder()
            .method("POST", uppercase(originalRequest.body()))
            .addHeader("OkHttp-Intercepted", "yep")
            .build());
      }
    });

    Request request = new Request.Builder()
        .url(server.url("/"))
        .addHeader("Original-Header", "foo")
        .method("PUT", RequestBody.create(MediaType.get("text/plain"), "abc"))
        .build();

    client.newCall(request).execute();

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("ABC", recordedRequest.getBody().readUtf8());
    assertEquals("foo", recordedRequest.getHeader("Original-Header"));
    assertEquals("yep", recordedRequest.getHeader("OkHttp-Intercepted"));
    assertEquals("POST", recordedRequest.getMethod());
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

    addInterceptor(network, new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        Response originalResponse = chain.proceed(chain.request());
        return originalResponse.newBuilder()
            .body(uppercase(originalResponse.body()))
            .addHeader("OkHttp-Intercepted", "yep")
            .build();
      }
    });

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();

    Response response = client.newCall(request).execute();
    assertEquals("ABC", response.body().string());
    assertEquals("yep", response.header("OkHttp-Intercepted"));
    assertEquals("foo", response.header("Original-Header"));
  }

  @Test public void multipleApplicationInterceptors() throws Exception {
    multipleInterceptors(false);
  }

  @Test public void multipleNetworkInterceptors() throws Exception {
    multipleInterceptors(true);
  }

  private void multipleInterceptors(boolean network) throws Exception {
    server.enqueue(new MockResponse());

    addInterceptor(network, new Interceptor() {
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
    addInterceptor(network, new Interceptor() {
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
        .url(server.url("/"))
        .build();

    Response response = client.newCall(request).execute();
    assertEquals(Arrays.asList("Cupcake", "Donut"),
        response.headers("Response-Interceptor"));

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals(Arrays.asList("Android", "Bob"),
        recordedRequest.getHeaders().values("Request-Interceptor"));
  }

  @Test public void asyncApplicationInterceptors() throws Exception {
    asyncInterceptors(false);
  }

  @Test public void asyncNetworkInterceptors() throws Exception {
    asyncInterceptors(true);
  }

  private void asyncInterceptors(boolean network) throws Exception {
    server.enqueue(new MockResponse());

    addInterceptor(network, new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        Response originalResponse = chain.proceed(chain.request());
        return originalResponse.newBuilder()
            .addHeader("OkHttp-Intercepted", "yep")
            .build();
      }
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
        .addInterceptor(new Interceptor() {
          @Override public Response intercept(Chain chain) throws IOException {
            Response response1 = chain.proceed(chain.request());
            response1.body().close();
            return chain.proceed(chain.request());
          }
        }).build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();

    Response response = client.newCall(request).execute();
    assertEquals(response.body().string(), "b");
  }

  /** Make sure interceptors can interact with the OkHttp client. */
  @Test public void interceptorMakesAnUnrelatedRequest() throws Exception {
    server.enqueue(new MockResponse().setBody("a")); // Fetched by interceptor.
    server.enqueue(new MockResponse().setBody("b")); // Fetched directly.

    client = client.newBuilder()
        .addInterceptor(new Interceptor() {
          @Override public Response intercept(Chain chain) throws IOException {
            if (chain.request().url().encodedPath().equals("/b")) {
              Request requestA = new Request.Builder()
                  .url(server.url("/a"))
                  .build();
              Response responseA = client.newCall(requestA).execute();
              assertEquals("a", responseA.body().string());
            }

            return chain.proceed(chain.request());
          }
        }).build();

    Request requestB = new Request.Builder()
        .url(server.url("/b"))
        .build();
    Response responseB = client.newCall(requestB).execute();
    assertEquals("b", responseB.body().string());
  }

  /** Make sure interceptors can interact with the OkHttp client asynchronously. */
  @Test public void interceptorMakesAnUnrelatedAsyncRequest() throws Exception {
    server.enqueue(new MockResponse().setBody("a")); // Fetched by interceptor.
    server.enqueue(new MockResponse().setBody("b")); // Fetched directly.

    client = client.newBuilder()
        .addInterceptor(new Interceptor() {
          @Override public Response intercept(Chain chain) throws IOException {
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
          }
        }).build();

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
    addInterceptor(network, new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        throw new RuntimeException("boom!");
      }
    });

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();

    try {
      client.newCall(request).execute();
      fail();
    } catch (RuntimeException expected) {
      assertEquals("boom!", expected.getMessage());
    }
  }

  @Test public void networkInterceptorModifiedRequestIsReturned() throws IOException {
    server.enqueue(new MockResponse());

    Interceptor modifyHeaderInterceptor = new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        return chain.proceed(chain.request().newBuilder()
            .header("User-Agent", "intercepted request")
            .build());
      }
    };

    client = client.newBuilder()
        .addNetworkInterceptor(modifyHeaderInterceptor)
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("User-Agent", "user request")
        .build();

    Response response = client.newCall(request).execute();
    assertNotNull(response.request().header("User-Agent"));
    assertEquals("user request", response.request().header("User-Agent"));
    assertEquals("intercepted request", response.networkResponse().request().header("User-Agent"));
  }

  @Test public void applicationInterceptorThrowsRuntimeExceptionAsynchronous() throws Exception {
    interceptorThrowsRuntimeExceptionAsynchronous(false);
  }

  @Test public void networkInterceptorThrowsRuntimeExceptionAsynchronous() throws Exception {
    interceptorThrowsRuntimeExceptionAsynchronous(true);
  }

  /**
   * When an interceptor throws an unexpected exception, asynchronous callers are left hanging. The
   * exception goes to the uncaught exception handler.
   */
  private void interceptorThrowsRuntimeExceptionAsynchronous(boolean network) throws Exception {
    addInterceptor(network, new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        throw new RuntimeException("boom!");
      }
    });

    ExceptionCatchingExecutor executor = new ExceptionCatchingExecutor();
    client = client.newBuilder()
        .dispatcher(new Dispatcher(executor))
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    client.newCall(request).enqueue(callback);

    assertEquals("boom!", executor.takeException().getMessage());
  }

  @Test public void applicationInterceptorReturnsNull() throws Exception {
    server.enqueue(new MockResponse());

    Interceptor interceptor = new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        chain.proceed(chain.request());
        return null;
      }
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
      assertEquals("interceptor " + interceptor + " returned null", expected.getMessage());
    }
  }

  @Test public void networkInterceptorReturnsNull() throws Exception {
    server.enqueue(new MockResponse());

    Interceptor interceptor = new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        chain.proceed(chain.request());
        return null;
      }
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
      assertEquals("interceptor " + interceptor + " returned null", expected.getMessage());
    }
  }

  @Test public void networkInterceptorReturnsConnectionOnEmptyBody() throws Exception {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .addHeader("Connection", "Close"));

    Interceptor interceptor = new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        Response response = chain.proceed(chain.request());
        assertNotNull(chain.connection());
        return response;
      }
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

    Interceptor interceptor = new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        return chain.proceed(chain.request()).newBuilder().body(null).build();
      }
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
      assertEquals("interceptor " + interceptor + " returned a response with no body",
          expected.getMessage());
    }
  }

  @Test public void networkInterceptorResponseMustHaveBody() throws Exception {
    server.enqueue(new MockResponse());

    Interceptor interceptor = new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        return chain.proceed(chain.request()).newBuilder().body(null).build();
      }
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
      assertEquals("interceptor " + interceptor + " returned a response with no body",
          expected.getMessage());
    }
  }

  @Test public void connectTimeout() throws Exception {
    Interceptor interceptor1 = new Interceptor() {
      @Override public Response intercept(Chain chainA) throws IOException {
        assertEquals(5000, chainA.connectTimeoutMillis());

        Chain chainB = chainA.withConnectTimeout(100, TimeUnit.MILLISECONDS);
        assertEquals(100, chainB.connectTimeoutMillis());

        return chainB.proceed(chainA.request());
      }
    };

    Interceptor interceptor2 = new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        assertEquals(100, chain.connectTimeoutMillis());
        return chain.proceed(chain.request());
      }
    };

    client = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .addInterceptor(interceptor1)
        .addInterceptor(interceptor2)
        .build();

    Request request1 = new Request.Builder()
        .url("http://" + TestUtil.UNREACHABLE_ADDRESS)
        .build();
    Call call = client.newCall(request1);

    try {
      call.execute();
      fail();
    } catch (SocketTimeoutException expected) {
    }
  }

  @Test public void chainWithReadTimeout() throws Exception {
    Interceptor interceptor1 = new Interceptor() {
      @Override public Response intercept(Chain chainA) throws IOException {
        assertEquals(5000, chainA.readTimeoutMillis());

        Chain chainB = chainA.withReadTimeout(100, TimeUnit.MILLISECONDS);
        assertEquals(100, chainB.readTimeoutMillis());

        return chainB.proceed(chainA.request());
      }
    };

    Interceptor interceptor2 = new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        assertEquals(100, chain.readTimeoutMillis());
        return chain.proceed(chain.request());
      }
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
    Interceptor interceptor1 = new Interceptor() {
      @Override public Response intercept(Chain chainA) throws IOException {
        assertEquals(5000, chainA.writeTimeoutMillis());

        Chain chainB = chainA.withWriteTimeout(100, TimeUnit.MILLISECONDS);
        assertEquals(100, chainB.writeTimeoutMillis());

        return chainB.proceed(chainA.request());
      }
    };

    Interceptor interceptor2 = new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        assertEquals(100, chain.writeTimeoutMillis());
        return chain.proceed(chain.request());
      }
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
        .post(RequestBody.create(MediaType.get("text/plain"), data))
        .build();
    Call call = client.newCall(request1);

    try {
      call.execute(); // we want this call to throw a SocketTimeoutException
      fail();
    } catch (SocketTimeoutException expected) {
    }
  }

  @Test public void chainCanCancelCall() throws Exception {
    final AtomicReference<Call> callRef = new AtomicReference<>();

    Interceptor interceptor = new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        Call call = chain.call();
        callRef.set(call);

        assertFalse(call.isCanceled());
        call.cancel();
        assertTrue(call.isCanceled());

        return chain.proceed(chain.request());
      }
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

    assertSame(call, callRef.get());
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

    public Exception takeException() throws Exception {
      return exceptions.take();
    }
  }
}
