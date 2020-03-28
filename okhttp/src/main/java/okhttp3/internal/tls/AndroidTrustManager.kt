package okhttp3.internal.tls

import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

interface AndroidTrustManager : X509TrustManager {
  @Suppress("unused")
  fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, host: String): List<X509Certificate>

  @Suppress("unused")
  fun isSameTrustConfiguration(host1: String, host2: String): Boolean
}
