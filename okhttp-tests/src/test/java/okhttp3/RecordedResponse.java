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

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A received response or failure recorded by the response recorder.
 */
public final class RecordedResponse {
  public final Request request;
  public final Response response;
  public final WebSocket webSocket;
  public final String body;
  public final IOException failure;

  public RecordedResponse(Request request, Response response, WebSocket webSocket, String body,
      IOException failure) {
    this.request = request;
    this.response = response;
    this.webSocket = webSocket;
    this.body = body;
    this.failure = failure;
  }

  public RecordedResponse assertRequestUrl(HttpUrl url) {
    assertEquals(url, request.url());
    return this;
  }

  public RecordedResponse assertRequestMethod(String method) {
    assertEquals(method, request.method());
    return this;
  }

  public RecordedResponse assertRequestHeader(String name, String... values) {
    assertEquals(Arrays.asList(values), request.headers(name));
    return this;
  }

  public RecordedResponse assertCode(int expectedCode) {
    assertEquals(expectedCode, response.code());
    return this;
  }

  public RecordedResponse assertSuccessful() {
    assertTrue(response.isSuccessful());
    return this;
  }

  public RecordedResponse assertNotSuccessful() {
    assertFalse(response.isSuccessful());
    return this;
  }

  public RecordedResponse assertHeader(String name, String... values) {
    assertEquals(Arrays.asList(values), response.headers(name));
    return this;
  }

  public RecordedResponse assertHeaders(Headers headers) {
    assertEquals(headers, response.headers());
    return this;
  }

  public RecordedResponse assertBody(String expectedBody) {
    assertEquals(expectedBody, body);
    return this;
  }

  public RecordedResponse assertHandshake() {
    Handshake handshake = response.handshake();
    assertNotNull(handshake.tlsVersion());
    assertNotNull(handshake.cipherSuite());
    assertNotNull(handshake.peerPrincipal());
    assertEquals(1, handshake.peerCertificates().size());
    assertNull(handshake.localPrincipal());
    assertEquals(0, handshake.localCertificates().size());
    return this;
  }

  /**
   * Asserts that the current response was redirected and returns the prior response.
   */
  public RecordedResponse priorResponse() {
    Response priorResponse = response.priorResponse();
    assertNotNull(priorResponse);
    assertNull(priorResponse.body());
    return new RecordedResponse(priorResponse.request(), priorResponse, null, null, null);
  }

  /**
   * Asserts that the current response used the network and returns the network response.
   */
  public RecordedResponse networkResponse() {
    Response networkResponse = response.networkResponse();
    assertNotNull(networkResponse);
    assertNull(networkResponse.body());
    return new RecordedResponse(networkResponse.request(), networkResponse, null, null, null);
  }

  /** Asserts that the current response didn't use the network. */
  public RecordedResponse assertNoNetworkResponse() {
    assertNull(response.networkResponse());
    return this;
  }

  /** Asserts that the current response didn't use the cache. */
  public RecordedResponse assertNoCacheResponse() {
    assertNull(response.cacheResponse());
    return this;
  }

  /**
   * Asserts that the current response used the cache and returns the cache response.
   */
  public RecordedResponse cacheResponse() {
    Response cacheResponse = response.cacheResponse();
    assertNotNull(cacheResponse);
    assertNull(cacheResponse.body());
    return new RecordedResponse(cacheResponse.request(), cacheResponse, null, null, null);
  }

  public RecordedResponse assertFailure(Class<?> exceptionClass) {
    assertTrue(exceptionClass.isInstance(failure));
    return this;
  }

  public RecordedResponse assertFailure(String... messages) {
    assertNotNull(failure);
    assertTrue(failure.getMessage(), Arrays.asList(messages).contains(failure.getMessage()));
    return this;
  }

  public RecordedResponse assertFailureMatches(String pattern) {
    assertNotNull(failure);
    assertTrue(failure.getMessage(), failure.getMessage().matches(pattern));
    return this;
  }

  public RecordedResponse assertSentRequestAtMillis(long minimum, long maximum) {
    assertDateInRange(minimum, response.sentRequestAtMillis(), maximum);
    return this;
  }

  public RecordedResponse assertReceivedResponseAtMillis(long minimum, long maximum) {
    assertDateInRange(minimum, response.receivedResponseAtMillis(), maximum);
    return this;
  }

  private void assertDateInRange(long minimum, long actual, long maximum) {
    assertTrue("actual " + format(actual) + " < minimum " + format(maximum), actual >= minimum);
    assertTrue("actual " + format(actual) + " > maximum " + format(minimum), actual <= maximum);
  }

  private String format(long time) {
    return new SimpleDateFormat("HH:mm:ss.SSS").format(new Date(time));
  }

  public String getBody() {
    return body;
  }
}
