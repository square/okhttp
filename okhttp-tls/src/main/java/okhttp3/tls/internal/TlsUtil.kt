/*
 * Copyright (C) 2012 Square, Inc.
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
package okhttp3.tls.internal

import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.InputStream
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

object TlsUtil {
  val password = "password".toCharArray()

  private val localhost: HandshakeCertificates by lazy {
    // Generate a self-signed cert for the server to serve and the client to trust.
    val heldCertificate = HeldCertificate.Builder()
        .commonName("localhost")
        .addSubjectAlternativeName(InetAddress.getByName("localhost").canonicalHostName)
        .build()
    return@lazy HandshakeCertificates.Builder()
        .heldCertificate(heldCertificate)
        .addTrustedCertificate(heldCertificate.certificate)
        .build()
  }

  /** Returns an SSL client for this host's localhost address. */
  @JvmStatic
  fun localhost(): HandshakeCertificates = localhost

  /** Returns a trust manager that trusts `trustedCertificates`. */
  @JvmStatic
  fun newTrustManager(
    keyStoreType: String?,
    trustedCertificates: List<X509Certificate>
  ): X509TrustManager {
    val trustStore = newEmptyKeyStore(keyStoreType)
    for (i in trustedCertificates.indices) {
      trustStore.setCertificateEntry("cert_$i", trustedCertificates[i])
    }

    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(trustStore)
    val result = factory.trustManagers!!
    check(result.size == 1 && result[0] is X509TrustManager) {
      "Unexpected trust managers: ${result.contentToString()}"
    }

    return result[0] as X509TrustManager
  }

  /**
   * Returns a key manager for the held certificate and its chain. Returns an empty key manager if
   * `heldCertificate` is null.
   */
  @JvmStatic
  fun newKeyManager(
    keyStoreType: String?,
    heldCertificate: HeldCertificate?,
    vararg intermediates: X509Certificate
  ): X509KeyManager {
    val keyStore = newEmptyKeyStore(keyStoreType)
    if (heldCertificate != null) {
      val chain = arrayOfNulls<Certificate>(1 + intermediates.size)
      chain[0] = heldCertificate.certificate
      intermediates.copyInto(chain, 1)
      keyStore.setKeyEntry("private", heldCertificate.keyPair.private, password, chain)
    }

    val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    factory.init(keyStore, password)
    val result = factory.keyManagers!!
    check(result.size == 1 && result[0] is X509KeyManager) {
      "Unexpected key managers:${result.contentToString()}"
    }

    return result[0] as X509KeyManager
  }

  private fun newEmptyKeyStore(keyStoreType: String?): KeyStore {
    return KeyStore.getInstance(keyStoreType ?: KeyStore.getDefaultType()).apply {
      val inputStream: InputStream? = null // By convention, 'null' creates an empty key store.
      load(inputStream, password)
    }
  }
}
