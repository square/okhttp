/*
 * Copyright (C) 2019 Square, Inc.
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
package okhttp3.internal.platform.android

import android.net.http.X509TrustManagerExtensions
import okhttp3.internal.tls.CertificateChainCleaner
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager

/**
 * Android Q+ implementation of CertificateChainCleaner using direct Android API calls.
 *
 * X509TrustManagerExtensions was added to Android in API 17 (Android 4.2, released in late 2012).
 * This is the best way to get a clean chain on Android because it uses the same code as the TLS
 * handshake.
 */
internal class Android10CertificateChainCleaner(
  private val trustManager: X509TrustManager
) : CertificateChainCleaner() {
  val extensions = X509TrustManagerExtensions(trustManager)

  @Suppress("UNCHECKED_CAST")
  @Throws(SSLPeerUnverifiedException::class)
  override
  fun clean(chain: List<Certificate>, hostname: String): List<Certificate> {
    val certificates = (chain as List<X509Certificate>).toTypedArray()
    try {
      return extensions.checkServerTrusted(certificates, "RSA", hostname)
    } catch (ce: CertificateException) {
      throw SSLPeerUnverifiedException(ce.message).apply { initCause(ce) }
    }
  }

  override fun equals(other: Any?): Boolean =
      other is Android10CertificateChainCleaner &&
          other.trustManager === this.trustManager

  override fun hashCode(): Int = System.identityHashCode(trustManager)
}