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

import okhttp3.Protocol
import okhttp3.internal.platform.AndroidPlatform
import okhttp3.internal.platform.Platform
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Modern reflection based SocketAdapter for Conscrypt class SSLSockets.
 */
open class AndroidSocketAdapter(private val sslSocketClass: Class<in SSLSocket>) :
    SocketAdapter {
  private val setUseSessionTickets: Method =
      sslSocketClass.getDeclaredMethod("setUseSessionTickets", Boolean::class.javaPrimitiveType)
  private val getAlpnSelectedProtocol = sslSocketClass.getMethod("getAlpnSelectedProtocol")
  private val setAlpnProtocols =
      sslSocketClass.getMethod("setAlpnProtocols", ByteArray::class.java)

  override fun isSupported(): Boolean = AndroidPlatform.isSupported

  override fun trustManager(sslSocketFactory: SSLSocketFactory): X509TrustManager? = null

  override fun matchesSocket(sslSocket: SSLSocket): Boolean = sslSocketClass.isInstance(sslSocket)

  override fun matchesSocketFactory(sslSocketFactory: SSLSocketFactory): Boolean = false

  override fun configureTlsExtensions(
    sslSocket: SSLSocket,
    protocols: List<Protocol>
  ) {
    // No TLS extensions if the socket class is custom.
    if (matchesSocket(sslSocket)) {
      try {
        // Enable session tickets.
        setUseSessionTickets.invoke(sslSocket, true)

        // Enable ALPN.
        setAlpnProtocols.invoke(sslSocket,
            Platform.concatLengthPrefixed(protocols))
      } catch (e: IllegalAccessException) {
        throw AssertionError(e)
      } catch (e: InvocationTargetException) {
        throw AssertionError(e)
      }
    }
  }

  override fun getSelectedProtocol(sslSocket: SSLSocket): String? {
    // No TLS extensions if the socket class is custom.
    if (!matchesSocket(sslSocket)) {
      return null
    }

    return try {
      val alpnResult = getAlpnSelectedProtocol.invoke(sslSocket) as ByteArray?
      if (alpnResult != null) String(alpnResult, StandardCharsets.UTF_8) else null
    } catch (e: NullPointerException) {
        when {
            // https://github.com/square/okhttp/issues/5587
            e.message == "ssl == null" -> null
            else -> throw e
        }
    } catch (e: IllegalAccessException) {
      throw AssertionError(e)
    } catch (e: InvocationTargetException) {
      throw AssertionError(e)
    }
  }

  companion object {
    fun buildIfSupported(packageName: String): SocketAdapter? {
      return try {
        @Suppress("UNCHECKED_CAST")
        val sslSocketClass = Class.forName("$packageName.OpenSSLSocketImpl") as Class<in SSLSocket>

        AndroidSocketAdapter(sslSocketClass)
      } catch (e: Exception) {
        androidLog(Platform.WARN, "unable to load android socket classes", e)
        null
      }
    }
  }
}
