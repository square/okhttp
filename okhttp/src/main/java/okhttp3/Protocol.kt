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
package okhttp3

import java.io.IOException

/**
 * Protocols that OkHttp implements for [ALPN][ietf_alpn] selection.
 *
 * ## Protocol vs Scheme
 *
 * Despite its name, [java.net.URL.getProtocol] returns the [scheme][java.net.URI.getScheme] (http,
 * https, etc.) of the URL, not the protocol (http/1.1, spdy/3.1, etc.). OkHttp uses the word
 * *protocol* to identify how HTTP messages are framed.
 *
 * [ietf_alpn]: http://tools.ietf.org/html/draft-ietf-tls-applayerprotoneg
 */
enum class Protocol(private val protocol: String) {
  /**
   * An obsolete plaintext framing that does not use persistent sockets by default.
   */
  HTTP_1_0("http/1.0"),

  /**
   * A plaintext framing that includes persistent connections.
   *
   * This version of OkHttp implements [RFC 7230][rfc_7230], and tracks revisions to that spec.
   *
   * [rfc_7230]: https://tools.ietf.org/html/rfc7230
   */
  HTTP_1_1("http/1.1"),

  /**
   * Chromium's binary-framed protocol that includes header compression, multiplexing multiple
   * requests on the same socket, and server-push. HTTP/1.1 semantics are layered on SPDY/3.
   *
   * Current versions of OkHttp do not support this protocol.
   */
  @Deprecated("OkHttp has dropped support for SPDY. Prefer {@link #HTTP_2}.")
  SPDY_3("spdy/3.1"),

  /**
   * The IETF's binary-framed protocol that includes header compression, multiplexing multiple
   * requests on the same socket, and server-push. HTTP/1.1 semantics are layered on HTTP/2.
   *
   * HTTP/2 requires deployments of HTTP/2 that use TLS 1.2 support
   * [CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256], present in Java 8+ and Android 5+.
   * Servers that enforce this may send an exception message including the string
   * `INADEQUATE_SECURITY`.
   */
  HTTP_2("h2"),

  /**
   * Cleartext HTTP/2 with no "upgrade" round trip. This option requires the client to have prior
   * knowledge that the server supports cleartext HTTP/2.
   *
   * See also [Starting HTTP/2 with Prior Knowledge][rfc_7540_34].
   *
   * [rfc_7540_34]: https://tools.ietf.org/html/rfc7540.section-3.4
   */
  H2_PRIOR_KNOWLEDGE("h2_prior_knowledge"),

  /**
   * QUIC (Quick UDP Internet Connection) is a new multiplexed and secure transport atop UDP,
   * designed from the ground up and optimized for HTTP/2 semantics. HTTP/1.1 semantics are layered
   * on HTTP/2.
   *
   * QUIC is not natively supported by OkHttp, but provided to allow a theoretical interceptor that
   * provides support.
   */
  QUIC("quic");

  /**
   * Returns the string used to identify this protocol for ALPN, like "http/1.1", "spdy/3.1" or
   * "h2".
   *
   * See also [IANA tls-extensiontype-values][iana].
   *
   * [iana]: https://www.iana.org/assignments/tls-extensiontype-values
   */
  override fun toString() = protocol

  companion object {
    /**
     * Returns the protocol identified by `protocol`.
     *
     * @throws IOException if `protocol` is unknown.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun get(protocol: String): Protocol {
      // Unroll the loop over values() to save an allocation.
      @Suppress("DEPRECATION")
      return when (protocol) {
        HTTP_1_0.protocol -> HTTP_1_0
        HTTP_1_1.protocol -> HTTP_1_1
        H2_PRIOR_KNOWLEDGE.protocol -> H2_PRIOR_KNOWLEDGE
        HTTP_2.protocol -> HTTP_2
        SPDY_3.protocol -> SPDY_3
        QUIC.protocol -> QUIC
        else -> throw IOException("Unexpected protocol: $protocol")
      }
    }
  }
}
