/*
 * Copyright (C) 2016 Square, Inc.
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

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Protocol

/** OpenJDK 9+.  */
class Jdk9Platform(
  @JvmField val setProtocolMethod: Method,
  @JvmField val getProtocolMethod: Method
) : Platform() {
  override fun configureTlsExtensions(
    sslSocket: SSLSocket,
    hostname: String?,
    protocols: List<Protocol>
  ) {
    try {
      val sslParameters = sslSocket.sslParameters

      val names = alpnProtocolNames(protocols)

      setProtocolMethod.invoke(sslParameters, names.toTypedArray())

      sslSocket.sslParameters = sslParameters
    } catch (e: IllegalAccessException) {
      throw AssertionError("failed to set SSL parameters", e)
    } catch (e: InvocationTargetException) {
      throw AssertionError("failed to set SSL parameters", e)
    }
  }

  override fun getSelectedProtocol(socket: SSLSocket): String? = try {
    val protocol = getProtocolMethod.invoke(socket) as String?

    // SSLSocket.getApplicationProtocol returns "" if application protocols values will not
    // be used. Observed if you didn't specify SSLParameters.setApplicationProtocols
    when (protocol) {
      null, "" -> null
      else -> protocol
    }
  } catch (e: IllegalAccessException) {
    throw AssertionError("failed to get ALPN selected protocol", e)
  } catch (e: InvocationTargetException) {
    throw AssertionError("failed to get ALPN selected protocol", e)
  }

  public override fun trustManager(sslSocketFactory: SSLSocketFactory): X509TrustManager? {
    // Not supported due to access checks on JDK 9+:
    // java.lang.reflect.InaccessibleObjectException: Unable to make member of class
    // sun.security.ssl.SSLSocketFactoryImpl accessible:  module java.base does not export
    // sun.security.ssl to unnamed module @xxx
    throw UnsupportedOperationException(
        "clientBuilder.sslSocketFactory(SSLSocketFactory) not supported on JDK 9+")
  }

  companion object {
    @JvmStatic
    fun buildIfSupported(): Jdk9Platform? =
        try {
          // Find JDK 9 methods
          val setProtocolMethod = SSLParameters::class.java.getMethod("setApplicationProtocols",
              Array<String>::class.java)
          val getProtocolMethod = SSLSocket::class.java.getMethod("getApplicationProtocol")

          Jdk9Platform(setProtocolMethod, getProtocolMethod)
        } catch (ignored: NoSuchMethodException) {
          // pre JDK 9
          null
        }
  }
}
