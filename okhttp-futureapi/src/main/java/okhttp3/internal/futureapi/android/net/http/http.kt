package okhttp3.internal.futureapi.android.net.http

import android.net.http.X509TrustManagerExtensions
import android.security.NetworkSecurityPolicy
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

fun isCleartextTrafficPermittedX(hostname: String): Boolean =
    NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(hostname)

class X509TrustManagerExtensionsX(trustManager: X509TrustManager) {
  val extensions = X509TrustManagerExtensions(trustManager)

  fun checkServerTrusted(
    certificates: Array<X509Certificate>,
    authType: String,
    host: String
  ): List<Certificate> {
    return extensions.checkServerTrusted(certificates, authType, host)
  }
}