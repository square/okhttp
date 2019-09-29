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
import okhttp3.internal.platform.Platform
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Deferred implementation of SocketAdapter that can only work by observing the socket
 * and initializing on first use.
 */
class DeferredSocketAdapter(private val socketPackage: String) : SocketAdapter {
  private var initialized = false
  private var delegate: SocketAdapter? = null

  override fun isSupported(): Boolean {
    return true
  }

  override fun matchesSocket(sslSocket: SSLSocket): Boolean {
    return sslSocket.javaClass.name.startsWith(socketPackage)
  }

  override fun configureTlsExtensions(
    sslSocket: SSLSocket,
    protocols: List<Protocol>
  ) {
    getDelegate(sslSocket)?.configureTlsExtensions(sslSocket, protocols)
  }

  override fun getSelectedProtocol(sslSocket: SSLSocket): String? {
    return getDelegate(sslSocket)?.getSelectedProtocol(sslSocket)
  }

  @Synchronized private fun getDelegate(actualSSLSocketClass: SSLSocket): SocketAdapter? {
    if (!initialized) {
      try {
        var possibleClass: Class<in SSLSocket> = actualSSLSocketClass.javaClass
        while (possibleClass.name != "$socketPackage.OpenSSLSocketImpl") {
          possibleClass = possibleClass.superclass

          if (possibleClass == null) {
            throw AssertionError(
                "No OpenSSLSocketImpl superclass of socket of type $actualSSLSocketClass")
          }
        }

        delegate = AndroidSocketAdapter(possibleClass)
      } catch (e: Exception) {
        Platform.get()
            .log("Failed to initialize DeferredSocketAdapter $socketPackage", Platform.WARN, e)
      }

      initialized = true
    }

    return delegate
  }

  override fun trustManager(sslSocketFactory: SSLSocketFactory): X509TrustManager? {
    // not supported with modern Android and opt-in Gms Provider
    return null
  }

  override fun matchesSocketFactory(sslSocketFactory: SSLSocketFactory): Boolean {
    return false
  }
}
