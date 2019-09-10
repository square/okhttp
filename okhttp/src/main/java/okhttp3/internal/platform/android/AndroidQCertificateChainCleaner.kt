package okhttp3.internal.platform.android

import android.net.http.X509TrustManagerExtensions
import okhttp3.internal.tls.CertificateChainCleaner
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager

/**
 * X509TrustManagerExtensions was added to Android in API 17 (Android 4.2, released in late 2012).
 * This is the best way to get a clean chain on Android because it uses the same code as the TLS
 * handshake.
 */
internal class AndroidQCertificateChainCleaner(
  trustManager: X509TrustManager
) : CertificateChainCleaner() {
  val extensions = X509TrustManagerExtensions(trustManager)

  @Suppress("UNCHECKED_CAST")
  @Throws(SSLPeerUnverifiedException::class)
  override // Reflection on List<Certificate>.
  fun clean(chain: List<Certificate>, hostname: String): List<Certificate> {
    val certificates = (chain as List<X509Certificate>).toTypedArray()
    return extensions.checkServerTrusted(certificates, "RSA", hostname)
  }

  override fun equals(other: Any?): Boolean =
      other is AndroidQCertificateChainCleaner // All instances are equivalent.

  override fun hashCode(): Int = 1
}