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

import okhttp3.internal.toImmutableList
import okhttp3.internal.immutableListOf
import java.io.IOException
import java.security.Principal
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession

/**
 * A record of a TLS handshake. For HTTPS clients, the client is *local* and the remote server is
 * its *peer*.
 *
 * This value object describes a completed handshake. Use [ConnectionSpec] to set policy for new
 * handshakes.
 */
class Handshake private constructor(
  /**
   * Returns the TLS version used for this connection. This value wasn't tracked prior to OkHttp
   * 3.0. For responses cached by preceding versions this returns [TlsVersion.SSL_3_0].
   */
  @get:JvmName("tlsVersion") val tlsVersion: TlsVersion,

  /** Returns the cipher suite used for the connection. */
  @get:JvmName("cipherSuite") val cipherSuite: CipherSuite,

  /** Returns a possibly-empty list of certificates that identify the remote peer. */
  @get:JvmName("peerCertificates") val peerCertificates: List<Certificate>,

  /** Returns a possibly-empty list of certificates that identify this peer. */
  @get:JvmName("localCertificates") val localCertificates: List<Certificate>
) {

  @JvmName("-deprecated_tlsVersion")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "tlsVersion"),
      level = DeprecationLevel.WARNING)
  fun tlsVersion() = tlsVersion

  @JvmName("-deprecated_cipherSuite")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "cipherSuite"),
      level = DeprecationLevel.WARNING)
  fun cipherSuite() = cipherSuite

  @JvmName("-deprecated_peerCertificates")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "peerCertificates"),
      level = DeprecationLevel.WARNING)
  fun peerCertificates() = peerCertificates

  /** Returns the remote peer's principle, or null if that peer is anonymous. */
  @get:JvmName("peerPrincipal")
  val peerPrincipal: Principal?
    get() = (peerCertificates.firstOrNull() as? X509Certificate)?.subjectX500Principal

  @JvmName("-deprecated_peerPrincipal")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "peerPrincipal"),
      level = DeprecationLevel.WARNING)
  fun peerPrincipal() = peerPrincipal

  @JvmName("-deprecated_localCertificates")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "localCertificates"),
      level = DeprecationLevel.WARNING)
  fun localCertificates() = localCertificates

  /** Returns the local principle, or null if this peer is anonymous. */
  @get:JvmName("localPrincipal")
  val localPrincipal: Principal?
    get() = (localCertificates.firstOrNull() as? X509Certificate)?.subjectX500Principal

  @JvmName("-deprecated_localPrincipal")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "localPrincipal"),
      level = DeprecationLevel.WARNING)
  fun localPrincipal() = localPrincipal

  override fun equals(other: Any?): Boolean {
    return other is Handshake &&
        other.tlsVersion == tlsVersion &&
        other.cipherSuite == cipherSuite &&
        other.peerCertificates == peerCertificates &&
        other.localCertificates == localCertificates
  }

  override fun hashCode(): Int {
    var result = 17
    result = 31 * result + tlsVersion.hashCode()
    result = 31 * result + cipherSuite.hashCode()
    result = 31 * result + peerCertificates.hashCode()
    result = 31 * result + localCertificates.hashCode()
    return result
  }

  override fun toString(): String {
    return "Handshake{" +
        "tlsVersion=$tlsVersion " +
        "cipherSuite=$cipherSuite " +
        "peerCertificates=${peerCertificates.map { it.name }} " +
        "localCertificates=${localCertificates.map { it.name }}}"
  }

  private val Certificate.name: String
    get() = when (this) {
      is X509Certificate -> subjectDN.toString()
      else -> type
    }

  companion object {
    @Throws(IOException::class)
    @JvmStatic
    @JvmName("get")
    fun SSLSession.handshake(): Handshake {
      val cipherSuiteString = checkNotNull(cipherSuite) { "cipherSuite == null" }
      if ("SSL_NULL_WITH_NULL_NULL" == cipherSuiteString) {
        throw IOException("cipherSuite == SSL_NULL_WITH_NULL_NULL")
      }
      val cipherSuite = CipherSuite.forJavaName(cipherSuiteString)

      val tlsVersionString = checkNotNull(protocol) { "tlsVersion == null" }
      if ("NONE" == tlsVersionString) throw IOException("tlsVersion == NONE")
      val tlsVersion = TlsVersion.forJavaName(tlsVersionString)

      val peerCertificates: Array<Certificate>? = try {
        peerCertificates
      } catch (_: SSLPeerUnverifiedException) {
        null
      }

      val peerCertificatesList = if (peerCertificates != null) {
        immutableListOf(*peerCertificates)
      } else {
        emptyList()
      }

      val localCertificates = localCertificates
      val localCertificatesList = if (localCertificates != null) {
        immutableListOf(*localCertificates)
      } else {
        emptyList()
      }

      return Handshake(tlsVersion, cipherSuite, peerCertificatesList, localCertificatesList)
    }

    @Throws(IOException::class)
    @JvmName("-deprecated_get")
    @Deprecated(
        message = "moved to extension function",
        replaceWith = ReplaceWith(expression = "sslSession.handshake()"),
        level = DeprecationLevel.WARNING)
    fun get(sslSession: SSLSession) = sslSession.handshake()

    @JvmStatic
    fun get(
      tlsVersion: TlsVersion,
      cipherSuite: CipherSuite,
      peerCertificates: List<Certificate>,
      localCertificates: List<Certificate>
    ): Handshake {
      return Handshake(tlsVersion, cipherSuite, peerCertificates.toImmutableList(),
          localCertificates.toImmutableList())
    }
  }
}