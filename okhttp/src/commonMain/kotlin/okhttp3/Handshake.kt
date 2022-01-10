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
package okhttp3

/**
 * A record of a TLS handshake. For HTTPS clients, the client is *local* and the remote server is
 * its *peer*.
 *
 * This value object describes a completed handshake. Use [ConnectionSpec] to set policy for new
 * handshakes.
 */
expect class Handshake {
  /**
   * Returns the TLS version used for this connection. This value wasn't tracked prior to OkHttp
   * 3.0. For responses cached by preceding versions this returns [TlsVersion.SSL_3_0].
   */
  val tlsVersion: TlsVersion

  /** Returns the cipher suite used for the connection. */
  val cipherSuite: CipherSuite

  /** Returns a possibly-empty list of certificates that identify this peer. */
  val localCertificates: List<Certificate>

  /** Returns a possibly-empty list of certificates that identify the remote peer. */
  val peerCertificates: List<Certificate>

  override fun equals(other: Any?): Boolean

  override fun hashCode(): Int

  override fun toString(): String
}
