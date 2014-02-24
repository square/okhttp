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

import com.squareup.okhttp.internal.Util;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** A scripted response to be replayed by the mock web server. */
public final class MockResponse implements Cloneable {
  private static final String CHUNKED_BODY_HEADER = "Transfer-encoding: chunked";

  private String status = "HTTP/1.1 200 OK";
  private List<String> headers = new ArrayList<String>();

  /** The response body content, or null if {@code bodyStream} is set. */
  private byte[] body;
  /** The response body content, or null if {@code body} is set. */
  private InputStream bodyStream;

  private int throttleBytesPerPeriod = Integer.MAX_VALUE;
  private long throttlePeriod = 1;
  private TimeUnit throttleUnit = TimeUnit.SECONDS;

  private SocketPolicy socketPolicy = SocketPolicy.KEEP_OPEN;

  private int bodyDelayTimeMs = 0;

  private List<PushPromise> promises = new ArrayList<PushPromise>();

  /** Creates a new mock response with an empty body. */
  public MockResponse() {
    setBody(new byte[0]);
  }

  @Override public MockResponse clone() {
    try {
      MockResponse result = (MockResponse) super.clone();
      result.headers = new ArrayList<String>(headers);
      result.promises = new ArrayList<PushPromise>(promises);
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

  /** Returns the raw HTTP payload, or null if this response is streamed. */
  public byte[] getBody() {
    return body;
  }

  /** Returns an input stream containing the raw HTTP payload. */
  InputStream getBodyStream() {
    return bodyStream != null ? bodyStream : new ByteArrayInputStream(body);
  }

  public MockResponse setBody(byte[] body) {
    setHeader("Content-Length", body.length);
    this.body = body;
    this.bodyStream = null;
    return this;
  }

  public MockResponse setBody(InputStream bodyStream, long bodyLength) {
    setHeader("Content-Length", bodyLength);
    this.body = null;
    this.bodyStream = bodyStream;
    return this;
  }

  /** Sets the response body to the UTF-8 encoded bytes of {@code body}. */
  public MockResponse setBody(String body) {
    try {
      return setBody(body.getBytes("UTF-8"));
    } catch (UnsupportedEncodingException e) {
      throw new AssertionError();
    }
  }

  /**
   * Sets the response body to {@code body}, chunked every {@code maxChunkSize}
   * bytes.
   */
  public MockResponse setChunkedBody(byte[] body, int maxChunkSize) {
    removeHeader("Content-Length");
    headers.add(CHUNKED_BODY_HEADER);

    try {
      ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
      int pos = 0;
      while (pos < body.length) {
        int chunkSize = Math.min(body.length - pos, maxChunkSize);
        bytesOut.write(Integer.toHexString(chunkSize).getBytes(Util.US_ASCII));
        bytesOut.write("\r\n".getBytes(Util.US_ASCII));
        bytesOut.write(body, pos, chunkSize);
        bytesOut.write("\r\n".getBytes(Util.US_ASCII));
        pos += chunkSize;
      }
      bytesOut.write("0\r\n\r\n".getBytes(Util.US_ASCII)); // Last chunk + empty trailer + crlf.

      this.body = bytesOut.toByteArray();
      return this;
    } catch (IOException e) {
      throw new AssertionError(); // In-memory I/O doesn't throw IOExceptions.
    }
  }

  /**
   * Sets the response body to the UTF-8 encoded bytes of {@code body}, chunked
   * every {@code maxChunkSize} bytes.
   */
  public MockResponse setChunkedBody(String body, int maxChunkSize) {
    try {
      return setChunkedBody(body.getBytes("UTF-8"), maxChunkSize);
    } catch (UnsupportedEncodingException e) {
      throw new AssertionError();
    }
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
   * When {@link MockWebServer#setNpnProtocols(java.util.List) protocols}
   * include a SPDY variant, this attaches a pushed stream to this response.
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
