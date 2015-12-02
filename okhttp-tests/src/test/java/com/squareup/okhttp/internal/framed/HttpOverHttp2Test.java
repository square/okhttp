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
package com.squareup.okhttp.internal.framed;

import com.squareup.okhttp.Headers;
import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.PushPromise;
import com.squareup.okhttp.mockwebserver.RecordedRequest;
import java.net.HttpURLConnection;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HttpOverHttp2Test extends HttpOverSpdyTest {

  public HttpOverHttp2Test() {
    super(Protocol.HTTP_2);
    this.hostHeader = ":authority";
  }

  @Test public void serverSendsPushPromise_GET() throws Exception {
    PushPromise pushPromise = new PushPromise("GET", "/foo/bar", Headers.of("foo", "bar"),
        new MockResponse().setBody("bar").setStatus("HTTP/1.1 200 Sweet"));
    MockResponse response = new MockResponse()
        .setBody("ABCDE")
        .setStatus("HTTP/1.1 200 Sweet")
        .withPush(pushPromise);
    server.enqueue(response);

    connection = client.open(server.getUrl("/foo"));
    assertContent("ABCDE", connection, Integer.MAX_VALUE);
    assertEquals(200, connection.getResponseCode());
    assertEquals("Sweet", connection.getResponseMessage());

    RecordedRequest request = server.takeRequest();
    assertEquals("GET /foo HTTP/1.1", request.getRequestLine());
    assertEquals("https", request.getHeader(":scheme"));
    assertEquals(server.getHostName() + ":" + server.getPort(), request.getHeader(hostHeader));

    RecordedRequest pushedRequest = server.takeRequest();
    assertEquals("GET /foo/bar HTTP/1.1", pushedRequest.getRequestLine());
    assertEquals("bar", pushedRequest.getHeader("foo"));
  }

  @Test public void serverSendsPushPromise_HEAD() throws Exception {
    PushPromise pushPromise = new PushPromise("HEAD", "/foo/bar", Headers.of("foo", "bar"),
        new MockResponse().setStatus("HTTP/1.1 204 Sweet"));
    MockResponse response = new MockResponse()
        .setBody("ABCDE")
        .setStatus("HTTP/1.1 200 Sweet")
        .withPush(pushPromise);
    server.enqueue(response);

    connection = client.open(server.getUrl("/foo"));
    assertContent("ABCDE", connection, Integer.MAX_VALUE);
    assertEquals(200, connection.getResponseCode());
    assertEquals("Sweet", connection.getResponseMessage());

    RecordedRequest request = server.takeRequest();
    assertEquals("GET /foo HTTP/1.1", request.getRequestLine());
    assertEquals("https", request.getHeader(":scheme"));
    assertEquals(server.getHostName() + ":" + server.getPort(), request.getHeader(hostHeader));

    RecordedRequest pushedRequest = server.takeRequest();
    assertEquals("HEAD /foo/bar HTTP/1.1", pushedRequest.getRequestLine());
    assertEquals("bar", pushedRequest.getHeader("foo"));
  }

  /**
   * Push a setting that permits up to 2 concurrent streams, then make 3 concurrent requests and
   * confirm that the third concurrent request prepared a new connection.
   */
  @Test public void settingsLimitsMaxConcurrentStreams() throws Exception {
    Settings settings = new Settings();
    settings.set(Settings.MAX_CONCURRENT_STREAMS, 0, 2);

    // Read & write a full request to confirm settings are accepted.
    server.enqueue(new MockResponse().withSettings(settings));
    HttpURLConnection settingsConnection = client.open(server.getUrl("/"));
    assertContent("", settingsConnection, Integer.MAX_VALUE);

    server.enqueue(new MockResponse().setBody("ABC"));
    server.enqueue(new MockResponse().setBody("DEF"));
    server.enqueue(new MockResponse().setBody("GHI"));

    HttpURLConnection connection1 = client.open(server.getUrl("/"));
    connection1.connect();
    HttpURLConnection connection2 = client.open(server.getUrl("/"));
    connection2.connect();
    HttpURLConnection connection3 = client.open(server.getUrl("/"));
    connection3.connect();
    assertContent("ABC", connection1, Integer.MAX_VALUE);
    assertContent("DEF", connection2, Integer.MAX_VALUE);
    assertContent("GHI", connection3, Integer.MAX_VALUE);
    assertEquals(0, server.takeRequest().getSequenceNumber()); // Settings connection.
    assertEquals(1, server.takeRequest().getSequenceNumber()); // Reuse settings connection.
    assertEquals(2, server.takeRequest().getSequenceNumber()); // Reuse settings connection.
    assertEquals(0, server.takeRequest().getSequenceNumber()); // New connection!
  }
}
