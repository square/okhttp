package okhttp3.internal.platform.android

import okhttp3.TrustManagerOverride
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

internal class TrustManagerWrapperAndroid(val trustManager: X509TrustManager): X509TrustManager {
  val delegateMethod by lazy { lookupAndroidDelegateMethod() }

  override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String) {
    trustManager.checkServerTrusted(chain, authType)
  }

  override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
    trustManager.checkClientTrusted(chain, authType)
  }

  override fun getAcceptedIssuers(): Array<X509Certificate> = trustManager.acceptedIssuers

  fun checkServerTrusted(
    chain: Array<out X509Certificate>,
    authType: String,
    host: String
  ): List<Certificate> {
    println("Running security checks for $host")
    println(chain.map { it.subjectDN.name }.take(1))

    return delegateMethod?.let {
      invokeDelegateMethod(it, chain, authType, host)
    }.orEmpty()
  }

  private fun lookupAndroidDelegateMethod(): Method? {
    return try {
      trustManager.javaClass.getMethod("checkServerTrusted",
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
      return delegateMethod.invoke(trustManager, chain, authType, host) as List<Certificate>
    } catch (ite: InvocationTargetException) {
      throw ite.targetException
    }
  }
}

internal class OkHttpTrustManagerAndroid(
  internal val default: TrustManagerWrapperAndroid,
  internal val overrides: List<TrustManagerOverride<TrustManagerWrapperAndroid>>
) : X509TrustManager {
  internal fun findByHost(peerHost: String): X509TrustManager {
    overrides.forEach {
      if (it.predicate(peerHost)) {
        return it.trustManager
      }
    }

    return default
  }

  fun checkServerTrusted(
    chain: Array<out X509Certificate>,
    authType: String,
    host: String
  ): List<Certificate> {
    val tm = findByHost(host)

    println("Running security checks for $host")
    println(chain.map { it.subjectDN.name }.take(1))

//    return invokeDelegateMethod(delegateMethod, chain, authType, host)
    TODO()
  }

  override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String) {
    default.checkServerTrusted(chain, authType)
  }

  override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
    default.checkClientTrusted(chain, authType)
  }

  override fun getAcceptedIssuers(): Array<X509Certificate> = default.acceptedIssuers
}