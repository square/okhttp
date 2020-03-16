package okhttp3

import android.annotation.SuppressLint
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.Socket
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.internal.platform.Platform
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

interface OkHttpTrustManager : X509TrustManager {
  fun checkServerTrusted(
    chain: Array<out X509Certificate>,
    authType: String,
    host: String
  ): List<Certificate>?

  val delegate: X509TrustManager
  val allowedPredicate: (String) -> Boolean

  companion object {
    @IgnoreJRERequirement
    @SuppressLint("NewApi")
    fun create(delegate: X509TrustManager, allowedPredicate: (String) -> Boolean): OkHttpTrustManager {
      return if (Platform.get().isAndroid) {
        OkHttpTrustManagerAndroid(delegate, allowedPredicate)
      } else {
        OkHttpTrustManagerJvm(delegate as X509ExtendedTrustManager, allowedPredicate)
      }
    }
  }
}

@IgnoreJRERequirement
@SuppressLint("NewApi")
class OkHttpTrustManagerJvm(
  override val delegate: X509ExtendedTrustManager,
  override val allowedPredicate: (String) -> Boolean
) : X509ExtendedTrustManager(), OkHttpTrustManager {
  override fun checkServerTrusted(
    chain: Array<out X509Certificate>,
    authType: String,
    host: String
  ): List<Certificate>? {
    if (!allowedPredicate(host)) {
      delegate.checkServerTrusted(chain, authType)
    }
    return null
  }

  override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
    delegate.checkServerTrusted(chain, authType)
  }

  override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
    delegate.checkClientTrusted(chain, authType)
  }

  override fun checkServerTrusted(
    chain: Array<out X509Certificate>,
    authType: String,
    socket: Socket
  ) {
    if (!allowedPredicate(socket.inetAddress.hostName)) {
      delegate.checkServerTrusted(chain, authType, socket)
    }
  }

  override fun checkServerTrusted(
    chain: Array<out X509Certificate>,
    authType: String,
    engine: SSLEngine
  ) {
    if (!allowedPredicate(engine.peerHost)) {
      delegate.checkServerTrusted(chain, authType, engine)
    }
  }

  override fun checkClientTrusted(
    chain: Array<out X509Certificate>,
    authType: String,
    socket: Socket
  ) {
    if (!allowedPredicate(socket.inetAddress.hostName)) {
      delegate.checkClientTrusted(chain, authType, socket)
    }
  }

  override fun checkClientTrusted(
    chain: Array<out X509Certificate>,
    authType: String,
    engine: SSLEngine
  ) {
    if (!allowedPredicate(engine.peerHost)) {
      delegate.checkClientTrusted(chain, authType, engine)
    }
  }

  override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}

class OkHttpTrustManagerAndroid(
  override val delegate: X509TrustManager,
  override val allowedPredicate: (String) -> Boolean
) : OkHttpTrustManager {
  val delegateMethod = lookupDelegateMethod()

  override fun checkServerTrusted(
    chain: Array<out X509Certificate>,
    authType: String,
    host: String
  ): List<Certificate> {

    if (allowedPredicate(host)) {
      println("Skipping security checks for $host")
      println(chain.map { it.subjectDN.name })

      return listOf()
    }

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
