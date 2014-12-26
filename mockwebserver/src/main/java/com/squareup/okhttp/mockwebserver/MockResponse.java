/*
 * Copyright (C) 2011 Google Inc.
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
package com.squareup.okhttp.mockwebserver;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okio.Buffer;
import okio.Okio;
import okio.Source;

/** A scripted response to be replayed by the mock web server. */
public final class MockResponse implements Cloneable {
  private static final String CHUNKED_BODY_HEADER = "Transfer-encoding: chunked";

  private String status = "HTTP/1.1 200 OK";
  private List<String> headers = new ArrayList<>();

  private Source body;
  private long bodyLength = -1L;

  private int throttleBytesPerPeriod = Integer.MAX_VALUE;
  private long throttlePeriod = 1;
  private TimeUnit throttleUnit = TimeUnit.SECONDS;

  private SocketPolicy socketPolicy = SocketPolicy.KEEP_OPEN;

  private int bodyDelayTimeMs = 0;

  private List<PushPromise> promises = new ArrayList<>();

  /** Creates a new mock response with an empty body. */
  public MockResponse() {
    setBody(new Buffer());
  }

  @Override public MockResponse clone() {
    try {
      MockResponse result = (MockResponse) super.clone();
      result.headers = new ArrayList<>(headers);
      result.promises = new ArrayList<>(promises);
      return result;
    } catch (CloneNotSupportedException e) {
      throw new AssertionError();
    }
  }

  /** Returns the HTTP response line, such as "HTTP/1.1 200 OK". */
  public String getStatus() {
    return status;
  }

  public MockResponse setResponseCode(int code) {
    this.status = "HTTP/1.1 " + code + " OK";
    return this;
  }

  public MockResponse setStatus(String status) {
    this.status = status;
    return this;
  }

  /** Returns the HTTP headers, such as "Content-Length: 0". */
  public List<String> getHeaders() {
    return headers;
  }

  /**
   * Removes all HTTP headers including any "Content-Length" and
   * "Transfer-encoding" headers that were added by default.
   */
  public MockResponse clearHeaders() {
    headers.clear();
    return this;
  }

  /**
   * Adds {@code header} as an HTTP header. For well-formed HTTP {@code header}
   * should contain a name followed by a colon and a value.
   */
  public MockResponse addHeader(String header) {
    headers.add(header);
    return this;
  }

  /**
   * Adds a new header with the name and value. This may be used to add multiple
   * headers with the same name.
   */
  public MockResponse addHeader(String name, Object value) {
    return addHeader(name + ": " + String.valueOf(value));
  }

  /**
   * Removes all headers named {@code name}, then adds a new header with the
   * name and value.
   */
  public MockResponse setHeader(String name, Object value) {
    removeHeader(name);
    return addHeader(name, value);
  }

  /** Removes all headers named {@code name}. */
  public MockResponse removeHeader(String name) {
    name += ":";
    for (Iterator<String> i = headers.iterator(); i.hasNext(); ) {
      String header = i.next();
      if (name.regionMatches(true, 0, header, 0, name.length())) {
        i.remove();
      }
    }
    return this;
  }

  /** Returns the raw HTTP payload. */
  public Source getBody() {
    return body;
  }

  /** Returns the length of {@link #getBody()} or {@code -1} if streamed. */
  public long getBodyLength() {
    return bodyLength;
  }

  public MockResponse setBody(byte[] body) {
    return setBody(new Buffer().write(body));
  }

  public MockResponse setBody(Buffer body) {
    return setBody(body.clone(), body.size());
  }

  public MockResponse setBody(InputStream bodyStream, long bodyLength) {
    return setBody(Okio.source(bodyStream), bodyLength);
  }

  public MockResponse setBody(Source body, long bodyLength) {
    setHeader("Content-Length", bodyLength);
    this.bodyLength = bodyLength;
    this.body = body;
    return this;
  }

  /** Sets the response body to the UTF-8 encoded bytes of {@code body}. */
  public MockResponse setBody(String body) {
    return setBody(new Buffer().writeUtf8(body));
  }

  /**
   * Sets the response body to {@code body}, chunked every {@code maxChunkSize}
   * bytes.
   */
  public MockResponse setChunkedBody(Buffer body, int maxChunkSize) {
    removeHeader("Content-Length");
    headers.add(CHUNKED_BODY_HEADER);

    Buffer bytesOut = new Buffer();
    while (!body.exhausted()) {
      long chunkSize = Math.min(body.size(), maxChunkSize);
      bytesOut.writeUtf8(Long.toHexString(chunkSize));
      bytesOut.writeUtf8("\r\n");
      bytesOut.write(body, chunkSize);
      bytesOut.writeUtf8("\r\n");
    }
    bytesOut.writeUtf8("0\r\n\r\n"); // Last chunk + empty trailer + CRLF.

    this.body = bytesOut;
    this.bodyLength = -1L; // Streamed
    return this;
  }

  /**
   * Sets the response body to the UTF-8 encoded bytes of {@code body}, chunked
   * every {@code maxChunkSize} bytes.
   */
  public MockResponse setChunkedBody(String body, int maxChunkSize) {
    return setChunkedBody(new Buffer().writeUtf8(body), maxChunkSize);
  }

  public SocketPolicy getSocketPolicy() {
    return socketPolicy;
  }

  public MockResponse setSocketPolicy(SocketPolicy socketPolicy) {
    this.socketPolicy = socketPolicy;
    return this;
  }

  /**
   * Throttles the response body writer to sleep for the given period after each
   * series of {@code bytesPerPeriod} bytes are written. Use this to simulate
   * network behavior.
   */
  public MockResponse throttleBody(int bytesPerPeriod, long period, TimeUnit unit) {
    this.throttleBytesPerPeriod = bytesPerPeriod;
    this.throttlePeriod = period;
    this.throttleUnit = unit;
    return this;
  }

  public int getThrottleBytesPerPeriod() {
    return throttleBytesPerPeriod;
  }

  public long getThrottlePeriod() {
    return throttlePeriod;
  }

  public TimeUnit getThrottleUnit() {
    return throttleUnit;
  }

  /**
   * Set the delayed time of the response body to {@code delay}. This applies to the
   * response body only; response headers are not affected.
   */
  public MockResponse setBodyDelayTimeMs(int delay) {
    bodyDelayTimeMs = delay;
    return this;
  }

  public int getBodyDelayTimeMs() {
    return bodyDelayTimeMs;
  }

  /**
   * When {@link MockWebServer#setProtocols(java.util.List) protocols}
   * include {@linkplain com.squareup.okhttp.Protocol#HTTP_2}, this attaches a
   * pushed stream to this response.
   */
  public MockResponse withPush(PushPromise promise) {
    this.promises.add(promise);
    return this;
  }

  /** Returns the streams the server will push with this response. */
  public List<PushPromise> getPushPromises() {
    return promises;
  }

  @Override public String toString() {
    return status;
  }
}
