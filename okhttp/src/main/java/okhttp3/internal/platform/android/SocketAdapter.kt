package okhttp3.internal.platform.android

import okhttp3.Protocol
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

interface SocketAdapter {
  open fun isSupported(): Boolean
  fun trustManager(sslSocketFactory: SSLSocketFactory): X509TrustManager?
  fun matchesSocket(sslSocket: SSLSocket): Boolean
  fun matchesSocketFactory(sslSocketFactory: SSLSocketFactory): Boolean

  open fun configureTlsExtensions(
    sslSocket: SSLSocket,
    hostname: String?,
    protocols: List<Protocol>
  )

  open fun getSelectedProtocol(sslSocket: SSLSocket): String?
}