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

import okhttp3.internal.Util
import java.io.IOException
import java.security.Principal
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession

/**
 * A record of a TLS handshake. For HTTPS clients, the client is *local* and the remote server
 * is its *peer*.
 *
 * This value object describes a completed handshake. Use [ConnectionSpec] to set policy
 * for new handshakes.
 */
data class Handshake private constructor(
  private val tlsVersion: TlsVersion,
  private val cipherSuite: CipherSuite,
  private val peerCertificates: List<Certificate>,
  private val localCertificates: List<Certificate>
) {

  /**
   * Returns the TLS version used for this connection. This value wasn't tracked prior to OkHttp
   * 3.0. For responses cached by preceding versions this returns [TlsVersion.SSL_3_0].
   */
  fun tlsVersion() = tlsVersion

  /** Returns the cipher suite used for the connection. */
  fun cipherSuite() = cipherSuite

  /** Returns a possibly-empty list of certificates that identify the remote peer. */
  fun peerCertificates() = peerCertificates

  /** Returns the remote peer's principle, or null if that peer is anonymous. */
  fun peerPrincipal(): Principal? {
    return if (peerCertificates.isNotEmpty()) {
      (peerCertificates[0] as X509Certificate).subjectX500Principal
    } else {
      null
    }
  }

  /** Returns a possibly-empty list of certificates that identify this peer. */
  fun localCertificates() = localCertificates

  /** Returns the local principle, or null if this peer is anonymous. */
  fun localPrincipal(): Principal? {
    return if (localCertificates.isNotEmpty()) {
      (localCertificates[0] as X509Certificate).subjectX500Principal
    } else {
      null
    }
  }

  override fun toString(): String {
    return "Handshake{" +
        "tlsVersion=" +
        tlsVersion +
        " cipherSuite=" +
        cipherSuite +
        " peerCertificates=" +
        names(peerCertificates) +
        " localCertificates=" +
        names(localCertificates) +
        "}"
  }

  private fun names(certificates: List<Certificate>): List<String> {
    return certificates
        .map {
          if (it is X509Certificate) {
            it.subjectDN.toString()
          } else {
            it.type
          }
        }
  }

  companion object {
    @Throws(IOException::class)
    @JvmStatic
    fun get(session: SSLSession): Handshake {
      val cipherSuiteString = session.cipherSuite ?: throw IllegalStateException("cipherSuite == null")
      if ("SSL_NULL_WITH_NULL_NULL" == cipherSuiteString) {
        throw IOException("cipherSuite == SSL_NULL_WITH_NULL_NULL")
      }
      val cipherSuite = CipherSuite.forJavaName(cipherSuiteString)

      val tlsVersionString = session.protocol ?: throw IllegalStateException("tlsVersion == null")
      if ("NONE" == tlsVersionString) throw IOException("tlsVersion == NONE")
      val tlsVersion = TlsVersion.forJavaName(tlsVersionString)

      val peerCertificates: Array<Certificate>? = try {
        session.peerCertificates
      } catch (ignored: SSLPeerUnverifiedException) {
        null
      }

      val peerCertificatesList = if (peerCertificates != null) {
        Util.immutableList(*peerCertificates)
      } else {
        emptyList()
      }

      val localCertificates = session.localCertificates
      val localCertificatesList = if (localCertificates != null) {
        Util.immutableList(*localCertificates)
      } else {
        emptyList()
      }

      return Handshake(tlsVersion, cipherSuite, peerCertificatesList, localCertificatesList)
    }

    @JvmStatic
    fun get(tlsVersion: TlsVersion, cipherSuite: CipherSuite,
            peerCertificates: List<Certificate>, localCertificates: List<Certificate>): Handshake {
      return Handshake(tlsVersion, cipherSuite, Util.immutableList(peerCertificates),
          Util.immutableList(localCertificates))
    }
  }
}