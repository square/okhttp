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
import java.lang.IllegalArgumentException
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager
import okhttp3.internal.SuppressSignatureCheck
import okhttp3.internal.tls.CertificateChainCleaner

/**
 * Android implementation of CertificateChainCleaner using direct Android API calls.
 * Not used if X509TrustManager doesn't implement [X509TrustManager.checkServerTrusted] with
 * an additional host param.
 */
internal class AndroidCertificateChainCleaner(
  private val trustManager: X509TrustManager,
  private val x509TrustManagerExtensions: X509TrustManagerExtensions
) : CertificateChainCleaner() {
  @Suppress("UNCHECKED_CAST")
  @Throws(SSLPeerUnverifiedException::class)
  @SuppressSignatureCheck
  override
  fun clean(chain: List<Certificate>, hostname: String): List<Certificate> {
    val certificates = (chain as List<X509Certificate>).toTypedArray()
    try {
      return x509TrustManagerExtensions.checkServerTrusted(certificates, "RSA", hostname)
    } catch (ce: CertificateException) {
      throw SSLPeerUnverifiedException(ce.message).apply { initCause(ce) }
    }
  }

  override fun equals(other: Any?): Boolean =
      other is AndroidCertificateChainCleaner &&
          other.trustManager === this.trustManager

  override fun hashCode(): Int = System.identityHashCode(trustManager)

  companion object {
    @SuppressSignatureCheck
    fun buildIfSupported(trustManager: X509TrustManager): AndroidCertificateChainCleaner? {
      val extensions = try {
        X509TrustManagerExtensions(trustManager)
      } catch (iae: IllegalArgumentException) {
        // X509TrustManagerExtensions checks for checkServerTrusted(X509Certificate[], String, String)
        null
      }

      return when {
        extensions != null -> AndroidCertificateChainCleaner(trustManager, extensions)
        else -> null
      }
    }
  }
}
