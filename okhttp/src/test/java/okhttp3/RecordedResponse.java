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
import javax.annotation.Nullable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A received response or failure recorded by the response recorder.
 */
public final class RecordedResponse {
  public final Request request;
  public final @Nullable Response response;
  public final @Nullable WebSocket webSocket;
  public final @Nullable String body;
  public final @Nullable IOException failure;

  public RecordedResponse(Request request, @Nullable Response response,
      @Nullable WebSocket webSocket, @Nullable String body, @Nullable IOException failure) {
    this.request = request;
    this.response = response;
    this.webSocket = webSocket;
    this.body = body;
    this.failure = failure;
  }

  public RecordedResponse assertRequestUrl(HttpUrl url) {
    assertThat(request.url()).isEqualTo(url);
    return this;
  }

  public RecordedResponse assertRequestMethod(String method) {
    assertThat(request.method()).isEqualTo(method);
    return this;
  }

  public RecordedResponse assertRequestHeader(String name, String... values) {
    assertThat(request.headers(name)).containsExactly(values);
    return this;
  }

  public RecordedResponse assertCode(int expectedCode) {
    assertThat(response.code()).isEqualTo(expectedCode);
    return this;
  }

  public RecordedResponse assertSuccessful() {
    assertThat(failure).isNull();
    assertThat(response.isSuccessful()).isTrue();
    return this;
  }

  public RecordedResponse assertNotSuccessful() {
    assertThat(response.isSuccessful()).isFalse();
    return this;
  }

  public RecordedResponse assertHeader(String name, String... values) {
    assertThat(response.headers(name)).containsExactly(values);
    return this;
  }

  public RecordedResponse assertHeaders(Headers headers) {
    assertThat(response.headers()).isEqualTo(headers);
    return this;
  }

  public RecordedResponse assertBody(String expectedBody) {
    assertThat(body).isEqualTo(expectedBody);
    return this;
  }

  public RecordedResponse assertHandshake() {
    Handshake handshake = response.handshake();
    assertThat(handshake.tlsVersion()).isNotNull();
    assertThat(handshake.cipherSuite()).isNotNull();
    assertThat(handshake.peerPrincipal()).isNotNull();
    assertThat(handshake.peerCertificates().size()).isEqualTo(1);
    assertThat(handshake.localPrincipal()).isNull();
    assertThat(handshake.localCertificates().size()).isEqualTo(0);
    return this;
  }

  /**
   * Asserts that the current response was redirected and returns the prior response.
   */
  public RecordedResponse priorResponse() {
    Response priorResponse = response.priorResponse();
    assertThat(priorResponse).isNotNull();
    assertThat(priorResponse.body()).isNull();
    return new RecordedResponse(priorResponse.request(), priorResponse, null, null, null);
  }

  /**
   * Asserts that the current response used the network and returns the network response.
   */
  public RecordedResponse networkResponse() {
    Response networkResponse = response.networkResponse();
    assertThat(networkResponse).isNotNull();
    assertThat(networkResponse.body()).isNull();
    return new RecordedResponse(networkResponse.request(), networkResponse, null, null, null);
  }

  /** Asserts that the current response didn't use the network. */
  public RecordedResponse assertNoNetworkResponse() {
    assertThat(response.networkResponse()).isNull();
    return this;
  }

  /** Asserts that the current response didn't use the cache. */
  public RecordedResponse assertNoCacheResponse() {
    assertThat(response.cacheResponse()).isNull();
    return this;
  }

  /**
   * Asserts that the current response used the cache and returns the cache response.
   */
  public RecordedResponse cacheResponse() {
    Response cacheResponse = response.cacheResponse();
    assertThat(cacheResponse).isNotNull();
    assertThat(cacheResponse.body()).isNull();
    return new RecordedResponse(cacheResponse.request(), cacheResponse, null, null, null);
  }

  public RecordedResponse assertFailure(Class<?>... allowedExceptionTypes) {
    boolean found = false;
    for (Class expectedClass : allowedExceptionTypes) {
      if (expectedClass.isInstance(failure)) {
        found = true;
        break;
      }
    }
    assertThat(found)
        .overridingErrorMessage("Expected exception type among "
            + Arrays.toString(allowedExceptionTypes) + ", got " + failure)
        .isTrue();
    return this;
  }

  public RecordedResponse assertFailure(String... messages) {
    assertThat(failure).overridingErrorMessage("No failure found").isNotNull();
    assertThat(messages).contains(failure.getMessage());
    return this;
  }

  public RecordedResponse assertFailureMatches(String... patterns) {
    assertThat(failure).isNotNull();
    for (String pattern : patterns) {
      if (failure.getMessage().matches(pattern)) return this;
    }
    throw new AssertionError(failure.getMessage());
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
    assertThat(actual)
        .overridingErrorMessage("%s <= %s <= %s", format(minimum), format(actual), format(maximum))
        .isBetween(minimum, maximum);

  }

  private String format(long time) {
    return new SimpleDateFormat("HH:mm:ss.SSS").format(new Date(time));
  }

  public String getBody() {
    return body;
  }
}
