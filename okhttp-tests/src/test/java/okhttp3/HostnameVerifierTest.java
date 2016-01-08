/*
 * Copyright (C) 2013 Square, Inc.
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

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import java.io.IOException;

import javax.net.ssl.SSLContext;

import okhttp3.HttpUrl.Builder;
import okhttp3.internal.SslContextBuilder;
import okhttp3.internal.tls.OkHostnameVerifier;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class HostnameVerifierTest {
  @Rule public final TestRule timeout = new Timeout(30_000);
  @Rule public MockWebServer server = new MockWebServer();

  private OkHttpClient client;
  private HttpUrl requestUrl;

  @Before public void setUp() throws Exception {
    client = new OkHttpClient.Builder().build();
    requestUrl = new Builder()
        .scheme("https")
        .host(server.getHostName())
        .port(server.getPort())
        .build();
  }

  @After public void tearDown() throws Exception {
  }

  @Test public void tls() throws Exception {
    useHostName();

    server.enqueue(new MockResponse()
        .setBody("abc")
        .addHeader("Content-Type: text/plain"));

    executeSynchronously(new Request.Builder().url(requestUrl).build())
        .assertHandshake();
  }

  @Test public void get_IpAddress() throws Exception {
    useIpAddress();
    get();
  }

  @Test public void get_HostName() throws Exception {
    useHostName();
    get();
  }

  private void get() throws Exception {
    server.enqueue(new MockResponse().setBody("abc")
        .addHeader("Content-Type: text/plain"));

    Request request = new Request.Builder()
        .url(requestUrl)
        .header("User-Agent", "SyncApiTest")
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertSuccessful()
        .assertHeader("Content-Type", "text/plain")
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("GET", recordedRequest.getMethod());
    assertEquals("SyncApiTest", recordedRequest.getHeader("User-Agent"));
    assertEquals(0, recordedRequest.getBody().size());
    assertNull(recordedRequest.getHeader("Content-Length"));
  }

  @Test public void post_IpAddress() throws Exception {
    useIpAddress();
    post();
  }

  @Test public void post_HostName() throws Exception {
    useHostName();
    post();
  }

  private void post() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request = new Request.Builder()
        .url(requestUrl)
        .post(RequestBody.create(MediaType.parse("text/plain"), "def"))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("POST", recordedRequest.getMethod());
    assertEquals("def", recordedRequest.getBody().readUtf8());
    assertEquals("3", recordedRequest.getHeader("Content-Length"));
    assertEquals("text/plain; charset=utf-8", recordedRequest.getHeader("Content-Type"));
  }

  @Test public void head_IpAddress() throws Exception {
    useIpAddress();
    head();
  }

  @Test public void head_HostName() throws Exception {
    useHostName();
    head();
  }

  private void head() throws Exception {
    server.enqueue(new MockResponse().addHeader("Content-Type: text/plain"));

    Request request = new Request.Builder()
        .url(requestUrl)
        .head()
        .header("User-Agent", "SyncApiTest")
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertHeader("Content-Type", "text/plain");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("HEAD", recordedRequest.getMethod());
    assertEquals("SyncApiTest", recordedRequest.getHeader("User-Agent"));
    assertEquals(0, recordedRequest.getBody().size());
    assertNull(recordedRequest.getHeader("Content-Length"));
  }

  @Test public void delete_IpAddress() throws Exception {
    useIpAddress();
    delete();
  }

  @Test public void delete_HostName() throws Exception {
    useHostName();
    delete();
  }

  private void delete() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request = new Request.Builder()
        .url(requestUrl)
        .delete()
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("DELETE", recordedRequest.getMethod());
    assertEquals(0, recordedRequest.getBody().size());
    assertEquals("0", recordedRequest.getHeader("Content-Length"));
    assertEquals(null, recordedRequest.getHeader("Content-Type"));
  }

  @Test public void put_IpAddress() throws Exception {
    useIpAddress();
    put();
  }

  @Test public void put_HostName() throws Exception {
    useHostName();
    put();
  }

  private void put() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request = new Request.Builder()
        .url(requestUrl)
        .put(RequestBody.create(MediaType.parse("text/plain"), "def"))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("PUT", recordedRequest.getMethod());
    assertEquals("def", recordedRequest.getBody().readUtf8());
    assertEquals("3", recordedRequest.getHeader("Content-Length"));
    assertEquals("text/plain; charset=utf-8", recordedRequest.getHeader("Content-Type"));
  }

  @Test public void patch_IpAddress() throws Exception {
    useIpAddress();
    patch();
  }

  @Test public void patch_HostName() throws Exception {
    useHostName();
    patch();
  }

  private void patch() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request = new Request.Builder()
        .url(requestUrl)
        .patch(RequestBody.create(MediaType.parse("text/plain"), "def"))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("PATCH", recordedRequest.getMethod());
    assertEquals("def", recordedRequest.getBody().readUtf8());
    assertEquals("3", recordedRequest.getHeader("Content-Length"));
    assertEquals("text/plain; charset=utf-8", recordedRequest.getHeader("Content-Type"));
  }

  private void useIpAddress() throws Exception {
    SSLContext sslContext = new SslContextBuilder("127.0.0.1").build();
    server.useHttps(sslContext.getSocketFactory(), false);

    client = client.newBuilder()
        .sslSocketFactory(sslContext.getSocketFactory())
        .hostnameVerifier(OkHostnameVerifier.INSTANCE)
        .build();

    requestUrl = requestUrl.newBuilder()
        .scheme("https")
        .host("127.0.0.1")
        .port(server.getPort())
        .build();
  }

  private void useHostName() throws Exception {
    SSLContext sslContext = SslContextBuilder.localhost();
    server.useHttps(sslContext.getSocketFactory(), false);

    client = new OkHttpClient.Builder()
        .sslSocketFactory(sslContext.getSocketFactory())
        .hostnameVerifier(OkHostnameVerifier.INSTANCE)
        .build();

    requestUrl = requestUrl.newBuilder()
        .scheme("https")
        .host(server.getHostName())
        .port(server.getPort())
        .build();
  }

  private RecordedResponse executeSynchronously(Request request) throws IOException {
    Response response = client.newCall(request).execute();
    return new RecordedResponse(request, response, null, response.body().string(), null);
  }
}
