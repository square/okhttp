/*
 * Copyright (C) 2016 Square, Inc.
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
package okhttp3.webserver;

import okhttp3.Headers;
import okhttp3.Protocol;
import okhttp3.WebSocketListener;
import okhttp3.internal.Internal;
import okhttp3.internal.http2.Settings;
import okio.Buffer;

import java.util.ArrayList;
import java.util.List;

/**
 * HTTP response sent back to clients after dispatching a {@link ClientRequest}.
 */
public final class ServerResponse implements Cloneable {
  private static final String CHUNKED_BODY_HEADER = "Transfer-encoding: chunked";

  private String status;
  private Headers.Builder headers = new Headers.Builder();

  private Buffer body;

  private List<PushPromise> promises = new ArrayList<>();
  private Settings settings;
  private WebSocketListener webSocketListener;
  private Protocol protocol;

  /** Creates a new response with an empty body. */
  public ServerResponse() {
    setResponseCode(200);
    setHeader("Content-Length", 0);
  }

  @Override public ServerResponse clone() {
    try {
      ServerResponse result = (ServerResponse) super.clone();
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

  public ServerResponse setResponseCode(int code) {
    String reason = "Mock ServerResponse";
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

  public ServerResponse setStatus(String status) {
    this.status = status;
    return this;
  }

  public Protocol protocol() {
    return protocol;
  }

  /** Returns the HTTP headers, such as "Content-Length: 0". */
  public Headers getHeaders() {
    return headers.build();
  }

  /**
   * Removes all HTTP headers including any "Content-Length" and "Transfer-encoding" headers that
   * were added by default.
   */
  public ServerResponse clearHeaders() {
    headers = new Headers.Builder();
    return this;
  }

  /**
   * Adds {@code header} as an HTTP header. For well-formed HTTP {@code header} should contain a
   * name followed by a colon and a value.
   */
  public ServerResponse addHeader(String header) {
    headers.add(header);
    return this;
  }

  /**
   * Adds a new header with the name and value. This may be used to add multiple headers with the
   * same name.
   */
  public ServerResponse addHeader(String name, Object value) {
    headers.add(name, String.valueOf(value));
    return this;
  }

  /**
   * Adds a new header with the name and value. This may be used to add multiple headers with the
   * same name. Unlike {@link #addHeader(String, Object)} this does not validate the name and
   * value.
   */
  public ServerResponse addHeaderLenient(String name, Object value) {
    Internal.instance.addLenient(headers, name, String.valueOf(value));
    return this;
  }

  /**
   * Removes all headers named {@code name}, then adds a new header with the name and value.
   */
  public ServerResponse setHeader(String name, Object value) {
    removeHeader(name);
    return addHeader(name, value);
  }

  /** Replaces all headers with those specified in {@code headers}. */
  public ServerResponse setHeaders(Headers headers) {
    this.headers = headers.newBuilder();
    return this;
  }

  /** Removes all headers named {@code name}. */
  public ServerResponse removeHeader(String name) {
    headers.removeAll(name);
    return this;
  }

  /** Returns a copy of the raw HTTP payload. */
  public Buffer getBody() {
    return body != null ? body.clone() : null;
  }

  public ServerResponse setBody(Buffer body) {
    setHeader("Content-Length", body.size());
    this.body = body.clone(); // Defensive copy.
    return this;
  }

  /** Sets the response body to the UTF-8 encoded bytes of {@code body}. */
  public ServerResponse setBody(String body) {
    return setBody(new Buffer().writeUtf8(body));
  }

  /**
   * Sets the response body to {@code body}, chunked every {@code maxChunkSize} bytes.
   */
  public ServerResponse setChunkedBody(Buffer body, int maxChunkSize) {
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
  public ServerResponse setChunkedBody(String body, int maxChunkSize) {
    return setChunkedBody(new Buffer().writeUtf8(body), maxChunkSize);
  }

  /**
   * When {@link OkHttpServer#setProtocols(java.util.List) protocols} include {@linkplain
   * okhttp3.Protocol#HTTP_2}, this attaches a pushed stream to this response.
   */
  public ServerResponse withPush(PushPromise promise) {
    this.promises.add(promise);
    return this;
  }

  /** Returns the streams the server will push with this response. */
  public List<PushPromise> getPushPromises() {
    return promises;
  }

  /**
   * When {@linkplain OkHttpServer#setProtocols(java.util.List) protocols} include {@linkplain
   * okhttp3.Protocol#HTTP_2 HTTP/2}, this pushes {@code settings} before writing the response.
   */
  public ServerResponse withSettings(Settings settings) {
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
  public ServerResponse withWebSocketUpgrade(WebSocketListener listener) {
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

  public void setProtocol(Protocol protocol) {
    this.protocol = protocol;
  }
}
