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
import com.squareup.okhttp.mockwebserver.MockWebServer;
import com.squareup.okhttp.mockwebserver.RecordedRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** Test how interceptors interact with HTTP requests and responses. */
public class InterceptorTest {

  @Rule public TemporaryFolder cacheRule = new TemporaryFolder();

  private final MockWebServer server = new MockWebServer();
  private final OkHttpClient client = new OkHttpClient();
  private Cache cache;

  @Before public void setUp() throws IOException {
    cache = new Cache(cacheRule.getRoot(), Integer.MAX_VALUE);
    client.setCache(cache);
  }

  @After public void tearDown() throws IOException {
    server.shutdown();
    cache.delete();
  }

  @Test public void transformUserAgentRequestCachedResponse() throws Exception {
    server.play();

    final Request smithRequest = new Request.Builder()
        .url(server.getUrl("/"))
        .addHeader("User-Agent", "smith")
        .build();

    server.enqueue(new MockResponse().addHeader("ETag: v1"));
    executeSynchronously(smithRequest);

    final RecordedRequest recordedSmithRequest = server.takeRequest();
    assertEquals("smith", recordedSmithRequest.getHeader("User-Agent"));
    assertEquals(1, cache.getRequestCount());
    assertEquals(1, cache.getNetworkCount());
    assertEquals(0, cache.getHitCount());

    final RequestInterceptor headerSmithToNeo = new RequestInterceptor() {
      public Request execute(Request request) {
        final Request.Builder result = request.newBuilder()
            .header("User-Agent", "neo");
        return result.build();
      }
    };

    final List<RequestInterceptor> requestInterceptors = client.requestInterceptors();
    requestInterceptors.add(headerSmithToNeo);

    server.enqueue(new MockResponse().addHeader("ETag: v1"));
    executeSynchronously(smithRequest);
    final RecordedRequest recordedNeoRequest = server.takeRequest();
    assertEquals("neo", recordedNeoRequest.getHeader("User-Agent"));
    assertEquals(2, cache.getRequestCount());
    assertEquals(2, cache.getNetworkCount());
    assertEquals(0, cache.getHitCount());
  }

  @Test public void transformStatusCodeResponse() throws IOException {
    server.play();

    final ResponseInterceptor header200To300 = new ResponseInterceptor() {
      public Response execute(Response response) {
        final Response.Builder result = response.newBuilder()
            .code(300);
        return result.build();
      }
    };

    final List<ResponseInterceptor> responseInterceptors = client.responseInterceptors();
    responseInterceptors.add(header200To300);
    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();
    server.enqueue(new MockResponse());
    executeSynchronously(request)
        .assertCode(300);
  }

  @Test public void transformServerHeaderResponse() throws IOException {
    server.play();

    final List<ResponseInterceptor> responseInterceptors = client.responseInterceptors();

    final ResponseInterceptor userAgentSwitch = new ResponseInterceptor() {
      public Response execute(Response response) {
        final Response.Builder result = response.newBuilder();
        result.header("server", "player1");
        return result.build();
      }
    };
    responseInterceptors.add(userAgentSwitch);
    final Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();
    server.enqueue(new MockResponse());
    executeSynchronously(request)
        .assertHeader("server", "player1");
  }

  @Test public void multipleTransformStatusCodeResponse() throws IOException {
    server.play();

    final ResponseInterceptor header200To300 = new ResponseInterceptor() {
      public Response execute(Response response) {
        final Response.Builder result = response.newBuilder();
        if (response.code() == 200) {
          result.code(300);
        }
        return result.build();
      }
    };
    final ResponseInterceptor header300To400 = new ResponseInterceptor() {
      public Response execute(Response response) {
        final Response.Builder result = response.newBuilder();
        if (response.code() == 300) {
          result.code(400);
        }
        return result.build();
      }
    };

    final List<ResponseInterceptor> responseInterceptors = client.responseInterceptors();
    responseInterceptors.add(header200To300);
    responseInterceptors.add(header300To400);
    final Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();
    server.enqueue(new MockResponse());
    executeSynchronously(request)
        .assertCode(400);

    // swap the orders with which the interceptors are called
    responseInterceptors.add(0, responseInterceptors.remove(1));
    server.enqueue(new MockResponse());
    executeSynchronously(request)
        .assertCode(300);
  }

  private RecordedResponse executeSynchronously(Request request) throws IOException {
    Response response = client.newCall(request).execute();
    return new RecordedResponse(request, response, response.body().string(), null);
  }
}
