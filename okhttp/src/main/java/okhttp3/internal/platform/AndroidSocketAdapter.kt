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
package okhttp3.internal.platform

import okhttp3.Protocol
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

open class AndroidSocketAdapter internal constructor(private val packageName: String) {
  internal lateinit var sslSocketClass: Class<in SSLSocket>
  internal lateinit var paramClass: Class<*>
  internal val setUseSessionTickets: Method by lazy {
    sslSocketClass.getDeclaredMethod("setUseSessionTickets", Boolean::class.javaPrimitiveType)
  }
  internal val setHostname by lazy {
    sslSocketClass.getMethod("setHostname", String::class.java)
  }
  internal val getAlpnSelectedProtocol by lazy {
    sslSocketClass.getMethod("getAlpnSelectedProtocol")
  }
  internal val setAlpnProtocols by lazy {
    sslSocketClass.getMethod("setAlpnProtocols", ByteArray::class.java)
  }

  open fun isSupported(): Boolean = AndroidPlatform.isSupported

  fun trustManager(sslSocketFactory: SSLSocketFactory): X509TrustManager? {
    val context: Any? =
        Platform.readFieldOrNull(sslSocketFactory, paramClass, "sslParameters")
    val x509TrustManager = Platform.readFieldOrNull(
        context!!, X509TrustManager::class.java, "x509TrustManager")
    return x509TrustManager ?: Platform.readFieldOrNull(context, X509TrustManager::class.java,
        "trustManager")
  }

  fun matchesSocket(sslSocket: SSLSocket): Boolean {
    val matches = matchesSocketInternal(sslSocket)

    if (matches) {
      // Avoid from scratch reflection, jump from the socket instance
      initSocketClasses(sslSocket.javaClass)
    }

    return matches
  }

  internal open fun matchesSocketInternal(sslSocket: SSLSocket) =
      sslSocket.javaClass.name.startsWith("${this.packageName}.")

  @Synchronized private fun initSocketClasses(actualSSLSocketClass: Class<in SSLSocket>) {
    if (!this::sslSocketClass.isInitialized) {
      var possibleClass: Class<in SSLSocket> = actualSSLSocketClass
      while (possibleClass.name != "$packageName.OpenSSLSocketImpl") {
        possibleClass = possibleClass.superclass

        if (possibleClass == null) {
          throw AssertionError(
              "No OpenSSLSocketImpl superclass of socket of type $actualSSLSocketClass")
        }
      }

      sslSocketClass = possibleClass
    }
  }

  fun matchesSocketFactory(sslSocketFactory: SSLSocketFactory): Boolean {
    val matches = matchesSocketFactoryInternal(sslSocketFactory)

    if (matches) {
      initSSLParameters(sslSocketFactory.javaClass)
    }

    return matches
  }

  internal open fun matchesSocketFactoryInternal(sslSocketFactory: SSLSocketFactory) =
      sslSocketFactory.javaClass.name.startsWith("${this.packageName}.")

  @Synchronized private fun initSSLParameters(sslSocketFactoryClass: Class<in SSLSocketFactory>) {
    if (!this::paramClass.isInitialized) {
      var possibleClass: Class<in SSLSocketFactory> = sslSocketFactoryClass
      while (possibleClass.name != "$packageName.OpenSSLSocketFactoryImpl") {
        possibleClass = possibleClass.superclass

        if (possibleClass == null) {
          throw AssertionError(
              "No OpenSSLSocketFactoryImpl superclass of socket of type $sslSocketFactoryClass")
        }
      }

      paramClass = possibleClass.getDeclaredField("sslParameters").type
    }
  }

  open fun configureTlsExtensions(
    sslSocket: SSLSocket,
    hostname: String?,
    protocols: List<Protocol>
  ) {
    // No TLS extensions if the socket class is custom.
    try {
      // Enable SNI and session tickets.
      if (hostname != null) {
        setUseSessionTickets.invoke(sslSocket, true)
        // This is SSLParameters.setServerNames() in API 24+.
        setHostname.invoke(sslSocket, hostname)
      }

      // Enable ALPN.
      setAlpnProtocols.invoke(sslSocket, Platform.concatLengthPrefixed(protocols))
    } catch (e: IllegalAccessException) {
      throw AssertionError(e)
    } catch (e: InvocationTargetException) {
      throw AssertionError(e)
    }
  }

  open fun getSelectedProtocol(sslSocket: SSLSocket): String? {
    return try {
      val alpnResult = getAlpnSelectedProtocol.invoke(sslSocket) as ByteArray?
      if (alpnResult != null) String(alpnResult, StandardCharsets.UTF_8) else null
    } catch (e: IllegalAccessException) {
      throw AssertionError(e)
    } catch (e: InvocationTargetException) {
      throw AssertionError(e)
    }
  }

  companion object {
    val Standard = AndroidSocketAdapter("com.android.org.conscrypt")
    val Gms = AndroidSocketAdapter("com.google.android.gms.org.conscrypt")
  }
}
