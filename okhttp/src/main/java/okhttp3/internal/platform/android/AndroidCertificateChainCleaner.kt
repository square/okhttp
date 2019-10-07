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

import okhttp3.internal.tls.CertificateChainCleaner
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager

/**
 * Legacy Android implementation of CertificateChainCleaner relying on reflection.
 *
 * X509TrustManagerExtensions was added to Android in API 17 (Android 4.2, released in late 2012).
 * This is the best way to get a clean chain on Android because it uses the same code as the TLS
 * handshake.
 */
internal class AndroidCertificateChainCleaner(
  private val trustManager: X509TrustManager,
  private val x509TrustManagerExtensions: Any,
  private val checkServerTrusted: Method
) : CertificateChainCleaner() {

  @Suppress("UNCHECKED_CAST") // Reflection on List<Certificate>
  @Throws(SSLPeerUnverifiedException::class)
  override
  fun clean(chain: List<Certificate>, hostname: String): List<Certificate> = try {
    val certificates = (chain as List<X509Certificate>).toTypedArray()
    checkServerTrusted.invoke(
        x509TrustManagerExtensions, certificates, "RSA", hostname) as List<Certificate>
  } catch (e: InvocationTargetException) {
    throw SSLPeerUnverifiedException(e.message).apply { initCause(e) }
  } catch (e: IllegalAccessException) {
    throw AssertionError(e)
  }

  override fun equals(other: Any?): Boolean =
      other is AndroidCertificateChainCleaner &&
          other.trustManager === this.trustManager

  override fun hashCode(): Int = System.identityHashCode(trustManager)

  companion object {
    fun build(trustManager: X509TrustManager): AndroidCertificateChainCleaner? = try {
      val extensionsClass = Class.forName("android.net.http.X509TrustManagerExtensions")
      val constructor = extensionsClass.getConstructor(X509TrustManager::class.java)
      val extensions = constructor.newInstance(trustManager)
      val checkServerTrusted = extensionsClass.getMethod(
          "checkServerTrusted", Array<X509Certificate>::class.java, String::class.java,
          String::class.java)
      AndroidCertificateChainCleaner(trustManager, extensions, checkServerTrusted)
    } catch (_: Exception) {
      null
    }
  }
}