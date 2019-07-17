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
    hostname: String?,
    protocols: List<Protocol>
  ) {
    getDelegate(sslSocket)?.configureTlsExtensions(sslSocket, hostname, protocols)
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
            .log(Platform.WARN, "Failed to initialize DeferredSocketAdapter $socketPackage", e)
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