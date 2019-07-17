package okhttp3.internal.platform.android

import okhttp3.internal.platform.Platform
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Base Android reflection based SocketAdapter for the built in Android SSLSocket.
 */
class StandardAndroidSocketAdapter(
  sslSocketClass: Class<in SSLSocket>,
  private val sslSocketFactoryClass: Class<in SSLSocketFactory>,
  private val paramClass: Class<*>
) : AndroidSocketAdapter(
    sslSocketClass) {

  override fun matchesSocketFactory(sslSocketFactory: SSLSocketFactory): Boolean =
      sslSocketFactoryClass.isInstance(sslSocketFactory)

  override fun trustManager(sslSocketFactory: SSLSocketFactory): X509TrustManager? {
    val context: Any? =
        Platform.readFieldOrNull(sslSocketFactory, paramClass,
            "sslParameters")
    val x509TrustManager = Platform.readFieldOrNull(
        context!!, X509TrustManager::class.java, "x509TrustManager")
    return x509TrustManager ?: Platform.readFieldOrNull(context,
        X509TrustManager::class.java,
        "trustManager")
  }

  companion object {
    @Suppress("UNCHECKED_CAST")
    fun buildIfSupported(packageName: String = "com.android.org.conscrypt"): SocketAdapter? {
      return try {
        val sslSocketClass = Class.forName("$packageName.OpenSSLSocketImpl") as Class<in SSLSocket>
        val sslSocketFactoryClass =
            Class.forName("$packageName.OpenSSLSocketFactoryImpl") as Class<in SSLSocketFactory>
        val paramsClass = Class.forName("$packageName.SSLParametersImpl")

        StandardAndroidSocketAdapter(sslSocketClass, sslSocketFactoryClass, paramsClass)
      } catch (e: Exception) {
        androidLog(Platform.WARN, "unable to load android socket classes", e)
        null
      }
    }
  }
}