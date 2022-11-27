package okhttp.android.test.sni

import android.os.Build
import android.util.Log
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import okhttp3.DelegatingSSLSocketFactory

class CustomSSLSocketFactory(
  delegate: SSLSocketFactory
) : DelegatingSSLSocketFactory(delegate) {
  override fun configureSocket(sslSocket: SSLSocket): SSLSocket {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      val parameters = sslSocket.sslParameters
      val sni = parameters.serverNames
      Log.d("CustomSSLSocketFactory", "old SNI: $sni")
      parameters.serverNames = mutableListOf<SNIServerName>(SNIHostName("cloudflare-dns.com"))
      sslSocket.sslParameters = parameters
    }

    return sslSocket
  }
}
