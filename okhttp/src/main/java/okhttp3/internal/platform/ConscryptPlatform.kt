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
package okhttp3.internal.platform

import okhttp3.Protocol
import org.conscrypt.Conscrypt
import java.security.NoSuchAlgorithmException
import java.security.Provider
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Platform using Conscrypt (conscrypt.org) if installed as the first Security Provider.
 *
 * Requires org.conscrypt:conscrypt-openjdk-uber on the classpath.
 */
class ConscryptPlatform private constructor() : Platform() {

  @Suppress("DEPRECATION")
  private val provider: Provider
    // defaults to true, but allow for older versions of conscrypt if still compatible
    // new form with boolean is only present in >= 2.0.0
    get() = Conscrypt.newProviderBuilder().provideTrustManager().build()

  override fun getSSLContext(): SSLContext {
    // Allow for Conscrypt 1.2
    return try {
      SSLContext.getInstance("TLSv1.3", provider)
    } catch (e: NoSuchAlgorithmException) {
      try {
        SSLContext.getInstance("TLS", provider)
      } catch (e2: NoSuchAlgorithmException) {
        throw IllegalStateException("No TLS provider", e)
      }
    }
  }

  public override fun trustManager(sslSocketFactory: SSLSocketFactory): X509TrustManager? =
      if (!Conscrypt.isConscrypt(sslSocketFactory)) {
        super.trustManager(sslSocketFactory)
      } else {
        try {
          // org.conscrypt.SSLParametersImpl
          val sp = readFieldOrNull(sslSocketFactory, Any::class.java, "sslParameters")

          when {
            sp != null -> readFieldOrNull(sp, X509TrustManager::class.java, "x509TrustManager")
            else -> null
          }
        } catch (e: Exception) {
          throw UnsupportedOperationException(
              "clientBuilder.sslSocketFactory(SSLSocketFactory) not supported on Conscrypt", e)
        }
      }

  override fun configureTlsExtensions(
    sslSocket: SSLSocket, hostname: String?, protocols: List<Protocol>
  ) {
    if (Conscrypt.isConscrypt(sslSocket)) {
      // Enable SNI and session tickets.
      if (hostname != null) {
        Conscrypt.setUseSessionTickets(sslSocket, true)
        Conscrypt.setHostname(sslSocket, hostname)
      }

      // Enable ALPN.
      val names = alpnProtocolNames(protocols)
      Conscrypt.setApplicationProtocols(sslSocket, names.toTypedArray())
    } else {
      super.configureTlsExtensions(sslSocket, hostname, protocols)
    }
  }

  override fun getSelectedProtocol(socket: SSLSocket): String? =
      if (Conscrypt.isConscrypt(socket)) {
        Conscrypt.getApplicationProtocol(socket)
      } else {
        super.getSelectedProtocol(socket)
      }

  override fun configureSslSocketFactory(socketFactory: SSLSocketFactory) {
    if (Conscrypt.isConscrypt(socketFactory)) {
      Conscrypt.setUseEngineSocket(socketFactory, true)
    }
  }

  companion object {
    @JvmStatic
    fun buildIfSupported(): ConscryptPlatform? = try {
      // Trigger an early exception over a fatal error, prefer a RuntimeException over Error.
      Class.forName("org.conscrypt.Conscrypt")

      when {
        Conscrypt.isAvailable() -> ConscryptPlatform()
        else -> null
      }
    } catch (e: ClassNotFoundException) {
      null
    }
  }
}
