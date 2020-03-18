/*
 * Copyright (C) 2020 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.tls.internal

import android.annotation.SuppressLint
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

internal open class TrustManagerOverride(
  val predicate: (String) -> Boolean,
  val trustManager: X509TrustManager
)

@IgnoreJRERequirement
@SuppressLint("NewApi")
class OkHttpTrustManagerJvm internal constructor(
  internal val default: X509TrustManager,
  internal val overrides: List<TrustManagerOverride>
) : X509ExtendedTrustManager() {

  internal fun findByHost(peerHost: String): X509TrustManager {
    overrides.forEach {
      println("Checking $peerHost against ${it.trustManager.javaClass.simpleName} ${it.trustManager.acceptedIssuers.size}")
      if (it.predicate(peerHost)) {
        return it.trustManager
      }
    }

    println("Using default ${default.javaClass.simpleName} ${default.acceptedIssuers.size}")

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
    val trustManager = findByHost(peerHost)

    println("Running security checks for $peerHost using ${trustManager.javaClass.simpleName}")
    println(chain.map { it.subjectDN.name }.take(1))

    if (trustManager is X509ExtendedTrustManager) {
      trustManager.checkServerTrusted(chain, authType, socket)
    } else {
      trustManager.checkServerTrusted(chain, authType)
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
