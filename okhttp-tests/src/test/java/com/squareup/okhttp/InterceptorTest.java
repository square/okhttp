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
import java.util.Arrays;
import java.util.Locale;
import okio.Buffer;
import okio.BufferedSink;
import okio.ForwardingSink;
import okio.ForwardingSource;
import okio.Okio;
import okio.Sink;
import okio.Source;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public final class InterceptorTest {
  @Rule public MockWebServerRule server = new MockWebServerRule();

  private OkHttpClient client = new OkHttpClient();
  private RecordingCallback callback = new RecordingCallback();

  @Test public void shortCircuitResponseBeforeConnection() throws Exception {
    server.get().shutdown(); // Accept no connections.

    Request request = new Request.Builder()
        .url("https://localhost:0/")
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

  @Test public void rewriteRequestToServer() throws Exception {
    server.enqueue(new MockResponse());

    client.interceptors().add(new Interceptor() {
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
    assertEquals("ABC", recordedRequest.getUtf8Body());
    assertEquals("foo", recordedRequest.getHeader("Original-Header"));
    assertEquals("yep", recordedRequest.getHeader("OkHttp-Intercepted"));
    assertEquals("POST", recordedRequest.getMethod());
  }

  @Test public void rewriteResponseFromServer() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Original-Header: foo")
        .setBody("abc"));

    client.interceptors().add(new Interceptor() {
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

  @Test public void multipleInterceptors() throws Exception {
    server.enqueue(new MockResponse());

    client.interceptors().add(new Interceptor() {
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
    client.interceptors().add(new Interceptor() {
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
        recordedRequest.getHeaders("Request-Interceptor"));
  }

  @Test public void asyncInterceptors() throws Exception {
    server.get().shutdown(); // Accept no connections.

    Request request = new Request.Builder()
        .url("https://localhost:0/")
        .build();

    final Response interceptorResponse = new Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("Intercepted!")
        .header("OkHttp-Intercepted", "yep")
        .body(ResponseBody.create(MediaType.parse("text/plain; charset=utf-8"), "abc"))
        .build();

    client.interceptors().add(new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        return interceptorResponse;
      }
    });

    client.newCall(request).enqueue(callback);

    callback.await(request.url())
        .assertCode(200)
        .assertBody("abc")
        .assertHeader("OkHttp-Intercepted", "yep");
  }

  @Test public void multipleRequestsToServer() throws Exception {
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

  private RequestBody uppercase(final RequestBody original) {
    return new RequestBody() {
      @Override public MediaType contentType() {
        return original.contentType();
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        Sink uppercase = uppercase(sink);
        BufferedSink bufferedSink = Okio.buffer(uppercase);
        original.writeTo(bufferedSink);

        // TODO: add BufferedSink.emit() to drain its buffer into its sink (without flush).
        uppercase.write(bufferedSink.buffer(), bufferedSink.buffer().size());
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

  static ResponseBody uppercase(ResponseBody original) {
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
}
