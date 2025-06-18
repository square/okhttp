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

package mockwebserver3

import java.io.IOException
import okhttp3.ExperimentalOkHttpApi
import okhttp3.Handshake
import okhttp3.Headers
import okhttp3.HttpUrl
import okio.ByteString

/** An HTTP request that came into the mock web server. */
@ExperimentalOkHttpApi
public class RecordedRequest(
  /**
   * The index of this request on its HTTP connection. Since a single HTTP connection may serve
   * multiple requests, each request is assigned its own sequence number.
   */
  public val sequenceNumber: Int,
  /**
   * The TLS handshake of the connection that carried this request, or null if the request was
   * received without TLS.
   */
  public val handshake: Handshake?,
  /**
   * Returns the name of the server the client requested via the SNI (Server Name Indication)
   * attribute in the TLS handshake. Unlike the rest of the HTTP exchange, this name is sent in
   * cleartext and may be monitored or blocked by a proxy or other middlebox.
   */
  public val handshakeServerNames: List<String>,
  public val method: String,
  /**
   * The request target from the original HTTP request.
   *
   * For origin-form requests this is a path like `/index.html`, that is combined with the `Host`
   * header to create the request URL.
   *
   * For HTTP proxy requests this will be either an absolute-form string like
   * `http://example.com/index.html` (HTTP proxy) or an authority-form string like
   * `example.com:443` (HTTPS proxy).
   *
   * For OPTIONS requests, this may be an asterisk, `*`.
   */
  public val target: String,
  /** A string like `HTTP/1.1`. */
  public val version: String,
  /** The request URL built using the request line, headers, and local host name. */
  public val url: HttpUrl,
  /** All headers. */
  public val headers: Headers,
  /** The body of this request, or null if it has none. This may be truncated. */
  public val body: ByteString?,
  /** The total size of the body of this request (before truncation).*/
  public val bodySize: Long,
  /**
   * The sizes of the chunks of this request's body, or an empty list if the request's body
   * was empty or unchunked.
   */
  public val chunkSizes: List<Int>,
  /**
   * The failure MockWebServer recorded when attempting to decode this request. If, for example,
   * the inbound request was truncated, this exception will be non-null.
   */
  public val failure: IOException? = null,
) {
  public val requestLine: String
    get() = "$method $target $version"

  public override fun toString(): String = requestLine
}
