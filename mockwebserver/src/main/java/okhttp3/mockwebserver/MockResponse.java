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
package okhttp3.mockwebserver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.Headers;
import okhttp3.internal.Internal;
import okhttp3.internal.http2.Settings;
import okhttp3.WebSocketListener;
import okio.Buffer;

/** A scripted response to be replayed by the mock web server. */
public final class MockResponse implements Cloneable {
  private static final String CHUNKED_BODY_HEADER = "Transfer-encoding: chunked";

  private String status;
  private Headers.Builder headers = new Headers.Builder();

  private Buffer body;

  private long throttleBytesPerPeriod = Long.MAX_VALUE;
  private long throttlePeriodAmount = 1;
  private TimeUnit throttlePeriodUnit = TimeUnit.SECONDS;

  private SocketPolicy socketPolicy = SocketPolicy.KEEP_OPEN;
  private int http2ErrorCode = -1;

  private long bodyDelayAmount = 0;
  private TimeUnit bodyDelayUnit = TimeUnit.MILLISECONDS;

  private long shutdownDelay = 0;
  private TimeUnit shutdownDelayUnit = TimeUnit.MILLISECONDS;

  private List<PushPromise> promises = new ArrayList<>();
  private Settings settings;
  private WebSocketListener webSocketListener;

  /** Creates a new mock response with an empty body. */
  public MockResponse() {
    setResponseCode(200);
    setHeader("Content-Length", 0);
  }

  @Override public MockResponse clone() {
    try {
      MockResponse result = (MockResponse) super.clone();
      result.headers = headers.build().newBuilder();
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
    String reason = "Mock Response";
    if (code >= 100 && code < 200) {
      reason = "Informational";
    } else if (code >= 200 && code < 300) {
      reason = "OK";
    } else if (code >= 300 && code < 400) {
      reason = "Redirection";
    } else if (code >= 400 && code < 500) {
      reason = "Client Error";
    } else if (code >= 500 && code < 600) {
      reason = "Server Error";
    }
    return setStatus("HTTP/1.1 " + code + " " + reason);
  }

  public MockResponse setStatus(String status) {
    this.status = status;
    return this;
  }

  /** Returns the HTTP headers, such as "Content-Length: 0". */
  public Headers getHeaders() {
    return headers.build();
  }

  /**
   * Removes all HTTP headers including any "Content-Length" and "Transfer-encoding" headers that
   * were added by default.
   */
  public MockResponse clearHeaders() {
    headers = new Headers.Builder();
    return this;
  }

  /**
   * Adds {@code header} as an HTTP header. For well-formed HTTP {@code header} should contain a
   * name followed by a colon and a value.
   */
  public MockResponse addHeader(String header) {
    headers.add(header);
    return this;
  }

  /**
   * Adds a new header with the name and value. This may be used to add multiple headers with the
   * same name.
   */
  public MockResponse addHeader(String name, Object value) {
    headers.add(name, String.valueOf(value));
    return this;
  }

  /**
   * Adds a new header with the name and value. This may be used to add multiple headers with the
   * same name. Unlike {@link #addHeader(String, Object)} this does not validate the name and
   * value.
   */
  public MockResponse addHeaderLenient(String name, Object value) {
    Internal.instance.addLenient(headers, name, String.valueOf(value));
    return this;
  }

  /**
   * Removes all headers named {@code name}, then adds a new header with the name and value.
   */
  public MockResponse setHeader(String name, Object value) {
    removeHeader(name);
    return addHeader(name, value);
  }

  /** Replaces all headers with those specified in {@code headers}. */
  public MockResponse setHeaders(Headers headers) {
    this.headers = headers.newBuilder();
    return this;
  }

  /** Removes all headers named {@code name}. */
  public MockResponse removeHeader(String name) {
    headers.removeAll(name);
    return this;
  }

  /** Returns a copy of the raw HTTP payload. */
  public Buffer getBody() {
    return body != null ? body.clone() : null;
  }

  public MockResponse setBody(Buffer body) {
    setHeader("Content-Length", body.size());
    this.body = body.clone(); // Defensive copy.
    return this;
  }

  /** Sets the response body to the UTF-8 encoded bytes of {@code body}. */
  public MockResponse setBody(String body) {
    return setBody(new Buffer().writeUtf8(body));
  }

  /**
   * Sets the response body to {@code body}, chunked every {@code maxChunkSize} bytes.
   */
  public MockResponse setChunkedBody(Buffer body, int maxChunkSize) {
    removeHeader("Content-Length");
    headers.add(CHUNKED_BODY_HEADER);

    Buffer bytesOut = new Buffer();
    while (!body.exhausted()) {
      long chunkSize = Math.min(body.size(), maxChunkSize);
      bytesOut.writeHexadecimalUnsignedLong(chunkSize);
      bytesOut.writeUtf8("\r\n");
      bytesOut.write(body, chunkSize);
      bytesOut.writeUtf8("\r\n");
    }
    bytesOut.writeUtf8("0\r\n\r\n"); // Last chunk + empty trailer + CRLF.

    this.body = bytesOut;
    return this;
  }

  /**
   * Sets the response body to the UTF-8 encoded bytes of {@code body}, chunked every {@code
   * maxChunkSize} bytes.
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

  public int getHttp2ErrorCode() {
    return http2ErrorCode;
  }

  /**
   * Sets the <a href="https://tools.ietf.org/html/rfc7540#section-7">HTTP/2 error code</a> to be
   * returned when resetting the stream. This is only valid with {@link
   * SocketPolicy#RESET_STREAM_AT_START}.
   */
  public MockResponse setHttp2ErrorCode(int http2ErrorCode) {
    this.http2ErrorCode = http2ErrorCode;
    return this;
  }

  /**
   * Throttles the request reader and response writer to sleep for the given period after each
   * series of {@code bytesPerPeriod} bytes are transferred. Use this to simulate network behavior.
   */
  public MockResponse throttleBody(long bytesPerPeriod, long period, TimeUnit unit) {
    this.throttleBytesPerPeriod = bytesPerPeriod;
    this.throttlePeriodAmount = period;
    this.throttlePeriodUnit = unit;
    return this;
  }

  public long getThrottleBytesPerPeriod() {
    return throttleBytesPerPeriod;
  }

  public long getThrottlePeriod(TimeUnit unit) {
    return unit.convert(throttlePeriodAmount, throttlePeriodUnit);
  }

  /**
   * Set the delayed time of the response body to {@code delay}. This applies to the response body
   * only; response headers are not affected.
   */
  public MockResponse setBodyDelay(long delay, TimeUnit unit) {
    bodyDelayAmount = delay;
    bodyDelayUnit = unit;
    return this;
  }

  public long getBodyDelay(TimeUnit unit) {
    return unit.convert(bodyDelayAmount, bodyDelayUnit);
  }

  /**
   * Set the time to {@code delay} prior to shutting down an HTTP/2 connection. This is only valid
   * with {@link SocketPolicy#DISCONNECT_AT_END}. Use this to simulate a graceful HTTP/2
   * connection shutdown.
   */
  public MockResponse setShutdownDelay(long delay, TimeUnit unit) {
    shutdownDelay = delay;
    shutdownDelayUnit = unit;
    return this;
  }

  public long getShutdownDelay(TimeUnit unit) {
    return unit.convert(shutdownDelay, shutdownDelayUnit);
  }

  /**
   * When {@link MockWebServer#setProtocols(java.util.List) protocols} include {@linkplain
   * okhttp3.Protocol#HTTP_2}, this attaches a pushed stream to this response.
   */
  public MockResponse withPush(PushPromise promise) {
    this.promises.add(promise);
    return this;
  }

  /** Returns the streams the server will push with this response. */
  public List<PushPromise> getPushPromises() {
    return promises;
  }

  /**
   * When {@linkplain MockWebServer#setProtocols(java.util.List) protocols} include {@linkplain
   * okhttp3.Protocol#HTTP_2 HTTP/2}, this pushes {@code settings} before writing the response.
   */
  public MockResponse withSettings(Settings settings) {
    this.settings = settings;
    return this;
  }

  public Settings getSettings() {
    return settings;
  }

  /**
   * Attempts to perform a web socket upgrade on the connection. This will overwrite any previously
   * set status or body.
   */
  public MockResponse withWebSocketUpgrade(WebSocketListener listener) {
    setStatus("HTTP/1.1 101 Switching Protocols");
    setHeader("Connection", "Upgrade");
    setHeader("Upgrade", "websocket");
    body = null;
    webSocketListener = listener;
    return this;
  }

  public WebSocketListener getWebSocketListener() {
    return webSocketListener;
  }

  @Override public String toString() {
    return status;
  }
}
