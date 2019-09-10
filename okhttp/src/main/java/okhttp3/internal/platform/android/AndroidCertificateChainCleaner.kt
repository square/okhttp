package okhttp3.internal.platform.android

import okhttp3.internal.tls.CertificateChainCleaner
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * X509TrustManagerExtensions was added to Android in API 17 (Android 4.2, released in late 2012).
 * This is the best way to get a clean chain on Android because it uses the same code as the TLS
 * handshake.
 */
internal class AndroidCertificateChainCleaner(
  private val x509TrustManagerExtensions: Any,
  private val checkServerTrusted: Method
) : CertificateChainCleaner() {

  @Suppress("UNCHECKED_CAST")
  @Throws(SSLPeerUnverifiedException::class)
  override // Reflection on List<Certificate>.
  fun clean(chain: List<Certificate>, hostname: String): List<Certificate> = try {
    val certificates = (chain as List<X509Certificate>).toTypedArray()
    checkServerTrusted.invoke(
        x509TrustManagerExtensions, certificates, "RSA", hostname) as List<Certificate>
  } catch (e: InvocationTargetException) {
    throw SSLPeerUnverifiedException(e.message).apply { initCause(e) }
  } catch (e: IllegalAccessException) {
    throw AssertionError(e)
  }

  override fun equals(other: Any?): Boolean =
      other is AndroidCertificateChainCleaner // All instances are equivalent.

  override fun hashCode(): Int = 0
}