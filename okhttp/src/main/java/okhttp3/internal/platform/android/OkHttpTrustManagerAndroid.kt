package okhttp3.internal.platform.android

import okhttp3.OkHttpTrustManager
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class OkHttpTrustManagerAndroid(
  internal val delegate: X509TrustManager,
  internal val overrides: List<(String) -> X509TrustManager?>
) : OkHttpTrustManager {
  val delegateMethod = lookupDelegateMethod()

  fun checkServerTrusted(
    chain: Array<out X509Certificate>,
    authType: String,
    host: String
  ): List<Certificate> {

    TODO()
//    if (allowedPredicate(host)) {
//      println("Skipping security checks for $host")
//      println(chain.map { it.subjectDN.name })
//
//      return listOf()
//    }

    println("Running security checks for $host")
    println(chain.map { it.subjectDN.name }.take(1))

    return invokeDelegateMethod(delegateMethod, chain, authType, host)
  }

  override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
    delegate.checkServerTrusted(chain, authType)
  }

  override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
    delegate.checkClientTrusted(chain, authType)
  }

  override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

  private fun lookupDelegateMethod(): Method {
    return delegate.javaClass.getMethod("checkServerTrusted",
        Array<X509Certificate>::class.java, String::class.java, String::class.java)
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