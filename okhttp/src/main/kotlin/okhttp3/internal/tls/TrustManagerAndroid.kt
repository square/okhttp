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
package okhttp3.internal.tls

import android.os.Build
import okhttp3.internal.SuppressSignatureCheck
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

internal open class TrustManagerOverrideAndroid(
  val predicate: (String) -> Boolean,
  val trustManager: AndroidTrustManager
)

internal class TrustManagerWrapperAndroid(val trustManager: X509TrustManager) : AndroidTrustManager {
  val delegateMethod by lazy { lookupAndroidDelegateMethod() }

  override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String) {
    trustManager.checkServerTrusted(chain, authType)
  }

  override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
    trustManager.checkClientTrusted(chain, authType)
  }

  override fun getAcceptedIssuers(): Array<X509Certificate> = trustManager.acceptedIssuers

  // Allows to keep using the X509TrustManagerExtensions to clean certificates
  override fun checkServerTrusted(
    chain: Array<X509Certificate>,
    authType: String,
    host: String
  ): List<X509Certificate> {
    if (trustManager is AndroidTrustManager) {
      return trustManager.checkServerTrusted(chain, authType, host)
    }

    // TODO review security implications here of orEmpty
    return delegateMethod?.let {
      invokeDelegateMethod(it, chain, authType, host)
    }.orEmpty()
  }

  override fun isSameTrustConfiguration(host1: String, host2: String): Boolean = false

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
  ): List<X509Certificate> {
    try {
      return delegateMethod.invoke(trustManager, chain, authType, host) as List<X509Certificate>
    } catch (ite: InvocationTargetException) {
      throw ite.targetException
    }
  }
}

@SuppressSignatureCheck
internal class TrustManagerAndroid(
  defaultTrustManager: X509TrustManager,
  overridesList: List<TrustManagerOverride>
) : AndroidTrustManager {
  init {
    check(Build.VERSION.SDK_INT >= 26) {
      "TrustManagerAndroid only supported on Android 26+"
    }
  }

  internal val default =
      TrustManagerWrapperAndroid(defaultTrustManager)
  internal val overrides = overridesList.map {
    TrustManagerOverrideAndroid(it.predicate, TrustManagerWrapperAndroid(it.trustManager))
  }

  internal fun findByHost(peerHost: String): AndroidTrustManager {
    overrides.forEach {
      if (it.predicate(peerHost)) {
        return it.trustManager
      }
    }

    return default
  }

  override fun checkServerTrusted(
    chain: Array<X509Certificate>,
    authType: String,
    host: String
  ): List<X509Certificate> {
    val trustManager = findByHost(host)

    return trustManager.checkServerTrusted(chain, authType, host)
  }

  override fun isSameTrustConfiguration(host1: String, host2: String) = false

  override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String) {
    default.checkServerTrusted(chain, authType)
  }

  override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
    default.checkClientTrusted(chain, authType)
  }

  override fun getAcceptedIssuers(): Array<X509Certificate> = default.acceptedIssuers
}
