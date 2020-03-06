package okhttp3.internal.tls

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class AllowlistedTrustManager(private val delegate: X509TrustManager, private vararg val hosts: String) : X509TrustManager {
  val delegateMethod = lookupDelegateMethod()

  override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) {
    delegate.checkClientTrusted(chain, authType)
  }

  override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
    delegate.checkServerTrusted(chain, authType)
  }

  fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, host: String): List<Certificate> {
    if (isAllowed(host)) {
      return listOf()
    }

    if (delegateMethod != null) {
      return invokeDelegateMethod(delegateMethod, chain, authType, host)
    }

    throw CertificateException("Failed to call checkServerTrusted")
  }

  fun isAllowed(host: String): Boolean = hosts.contains(host)

  override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

  private fun lookupDelegateMethod(): Method? {
    return try {
      delegate.javaClass.getMethod("checkServerTrusted",
          Array<X509Certificate>::class.java, String::class.java, String::class.java)
    } catch (nsme: NoSuchMethodException) {
      null
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun invokeDelegateMethod(
    delegateMethod: Method,
    chain: Array<out X509Certificate>,
    authType: String,
    host: String
  ): List<Certificate> {
    try {
      return delegateMethod.invoke(delegate, chain, authType, host) as List<Certificate>
    } catch (ite: InvocationTargetException) {
      throw ite.targetException
    }
  }
}
