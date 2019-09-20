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

import okhttp3.internal.immutableListOf
import okhttp3.internal.toImmutableList
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
class Handshake internal constructor(
  /**
   * Returns the TLS version used for this connection. This value wasn't tracked prior to OkHttp
   * 3.0. For responses cached by preceding versions this returns [TlsVersion.SSL_3_0].
   */
  @get:JvmName("tlsVersion") val tlsVersion: TlsVersion,

  /** Returns the cipher suite used for the connection. */
  @get:JvmName("cipherSuite") val cipherSuite: CipherSuite,

  /** Returns a possibly-empty list of certificates that identify this peer. */
  @get:JvmName("localCertificates") val localCertificates: List<Certificate>,

  // Delayed provider of peerCertificates, to allow lazy cleaning.
  peerCertificatesFn: () -> List<Certificate>
) {
  /** Returns a possibly-empty list of certificates that identify the remote peer. */
  @get:JvmName("peerCertificates") val peerCertificates: List<Certificate> by lazy(
      peerCertificatesFn)

  @JvmName("-deprecated_tlsVersion")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "tlsVersion"),
      level = DeprecationLevel.ERROR)
  fun tlsVersion() = tlsVersion

  @JvmName("-deprecated_cipherSuite")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "cipherSuite"),
      level = DeprecationLevel.ERROR)
  fun cipherSuite() = cipherSuite

  @JvmName("-deprecated_peerCertificates")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "peerCertificates"),
      level = DeprecationLevel.ERROR)
  fun peerCertificates() = peerCertificates

  /** Returns the remote peer's principle, or null if that peer is anonymous. */
  @get:JvmName("peerPrincipal")
  val peerPrincipal: Principal?
    get() = (peerCertificates.firstOrNull() as? X509Certificate)?.subjectX500Principal

  @JvmName("-deprecated_peerPrincipal")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "peerPrincipal"),
      level = DeprecationLevel.ERROR)
  fun peerPrincipal() = peerPrincipal

  @JvmName("-deprecated_localCertificates")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "localCertificates"),
      level = DeprecationLevel.ERROR)
  fun localCertificates() = localCertificates

  /** Returns the local principle, or null if this peer is anonymous. */
  @get:JvmName("localPrincipal")
  val localPrincipal: Principal?
    get() = (localCertificates.firstOrNull() as? X509Certificate)?.subjectX500Principal

  @JvmName("-deprecated_localPrincipal")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "localPrincipal"),
      level = DeprecationLevel.ERROR)
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
      val cipherSuite = when (cipherSuiteString) {
        "TLS_NULL_WITH_NULL_NULL", "SSL_NULL_WITH_NULL_NULL" -> {
          throw IOException("cipherSuite == $cipherSuiteString")
        }
        else -> CipherSuite.forJavaName(cipherSuiteString)
      }

      val tlsVersionString = checkNotNull(protocol) { "tlsVersion == null" }
      if ("NONE" == tlsVersionString) throw IOException("tlsVersion == NONE")
      val tlsVersion = TlsVersion.forJavaName(tlsVersionString)

      val peerCertificatesCopy = try {
        peerCertificates.toImmutableList()
      } catch (_: SSLPeerUnverifiedException) {
        listOf<Certificate>()
      }

      return Handshake(tlsVersion, cipherSuite,
          localCertificates.toImmutableList()) { peerCertificatesCopy }
    }

    private fun Array<out Certificate>?.toImmutableList(): List<Certificate> {
      return if (this != null) {
        immutableListOf(*this)
      } else {
        emptyList()
      }
    }

    @Throws(IOException::class)
    @JvmName("-deprecated_get")
    @Deprecated(
        message = "moved to extension function",
        replaceWith = ReplaceWith(expression = "sslSession.handshake()"),
        level = DeprecationLevel.ERROR)
    fun get(sslSession: SSLSession) = sslSession.handshake()

    @JvmStatic
    fun get(
      tlsVersion: TlsVersion,
      cipherSuite: CipherSuite,
      peerCertificates: List<Certificate>,
      localCertificates: List<Certificate>
    ): Handshake {
      val peerCertificatesCopy = peerCertificates.toImmutableList()
      return Handshake(tlsVersion, cipherSuite, localCertificates.toImmutableList()) {
        peerCertificatesCopy
      }
    }
  }
}
