package okhttp3.internal.platform

import android.annotation.SuppressLint
import okhttp3.TrustManagerOverride
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

@IgnoreJRERequirement
@SuppressLint("NewApi")
internal class OkHttpTrustManagerJvm(
  internal val default: X509TrustManager,
  internal val overrides: List<TrustManagerOverride<X509TrustManager>>
) : X509ExtendedTrustManager() {

  internal fun findByHost(peerHost: String): X509TrustManager {
    overrides.forEach {
      if (it.predicate(peerHost)) {
        return it.trustManager
      }
    }

    return default
  }

  override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
    default.checkClientTrusted(chain, authType)
  }

  override fun checkClientTrusted(
    chain: Array<out X509Certificate>,
    authType: String,
    engine: SSLEngine
  ) {
    // TODO should this use overrides or fail unsupported
    if (default is X509ExtendedTrustManager) {
      default.checkClientTrusted(chain, authType, engine)
    } else {
      default.checkClientTrusted(chain, authType)
    }
  }

  override fun checkClientTrusted(
    chain: Array<out X509Certificate>,
    authType: String,
    socket: Socket
  ) {
    // TODO should this use overrides or fail unsupported
    if (default is X509ExtendedTrustManager) {
      default.checkClientTrusted(chain, authType, socket)
    } else {
      default.checkClientTrusted(chain, authType)
    }
  }

  override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
    default.checkServerTrusted(chain, authType)
  }

  override fun checkServerTrusted(
    chain: Array<out X509Certificate>,
    authType: String,
    socket: Socket
  ) {
    val peerHost = socket.inetAddress.hostName
    val tm = findByHost(peerHost)

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
    val tm = findByHost(engine.peerHost)

    if (tm is X509ExtendedTrustManager) {
      tm.checkServerTrusted(chain, authType, engine)
    } else {
      tm.checkServerTrusted(chain, authType)
    }
  }

  override fun getAcceptedIssuers(): Array<X509Certificate> = default.acceptedIssuers
}