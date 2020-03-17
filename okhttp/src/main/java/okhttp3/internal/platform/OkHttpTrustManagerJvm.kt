package okhttp3.internal.platform

import android.annotation.SuppressLint
import okhttp3.OkHttpTrustManager
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
import java.net.Socket
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

@IgnoreJRERequirement
@SuppressLint("NewApi")
class OkHttpTrustManagerJvm(
  internal val delegate: X509TrustManager,
  internal val overrides: List<(String) -> X509TrustManager?>
) : X509ExtendedTrustManager(), OkHttpTrustManager {

  override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
    delegate.checkClientTrusted(chain, authType)
  }

  override fun checkClientTrusted(
    chain: Array<out X509Certificate>,
    authType: String,
    engine: SSLEngine
  ) {
    // TODO should this use overrides or fail unsupported
    if (delegate is X509ExtendedTrustManager) {
      delegate.checkClientTrusted(chain, authType, engine)
    } else {
      delegate.checkClientTrusted(chain, authType)
    }
  }

  override fun checkClientTrusted(
    chain: Array<out X509Certificate>,
    authType: String,
    socket: Socket
  ) {
    // TODO should this use overrides or fail unsupported
    if (delegate is X509ExtendedTrustManager) {
      delegate.checkClientTrusted(chain, authType, socket)
    } else {
      delegate.checkClientTrusted(chain, authType)
    }
  }

  override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
    delegate.checkServerTrusted(chain, authType)
  }

  override fun checkServerTrusted(
    chain: Array<out X509Certificate>,
    authType: String,
    socket: Socket
  ) {
    val peerHost = socket.inetAddress.hostName
    val tm = findTrustManager(peerHost)

    println("Running security checks for $peerHost using $tm")
    println(chain.map { it.subjectDN.name }.take(1))

    if (tm is X509ExtendedTrustManager) {
      tm.checkServerTrusted(chain, authType, socket)
    } else {
      tm.checkServerTrusted(chain, authType)
    }
  }

  override fun checkServerTrusted(
    chain: Array<out X509Certificate>,
    authType: String,
    engine: SSLEngine
  ) {
    val tm = findTrustManager(engine.peerHost)

    if (tm is X509ExtendedTrustManager) {
      tm.checkServerTrusted(chain, authType, engine)
    } else {
      tm.checkServerTrusted(chain, authType)
    }
  }

  private fun findTrustManager(peerHost: String): X509TrustManager {
    overrides.forEach {
      val tm = it(peerHost)
      if (tm != null) {
        return tm
      }
    }

    return delegate
  }

  override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}