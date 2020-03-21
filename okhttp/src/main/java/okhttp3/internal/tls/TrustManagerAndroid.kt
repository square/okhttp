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

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

internal open class TrustManagerOverrideAndroid(
  val predicate: (String) -> Boolean,
  val trustManager: TrustManagerWrapperAndroid
)

internal class TrustManagerWrapperAndroid(val trustManager: X509TrustManager) : X509TrustManager {
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
  val defaultTrustManager: X509TrustManager,
  overridesList: List<TrustManagerOverride>
) : X509TrustManager {
  internal val default =
      TrustManagerWrapperAndroid(defaultTrustManager)
  internal val overrides = overridesList.map {
    TrustManagerOverrideAndroid(it.predicate, TrustManagerWrapperAndroid(it.trustManager))
  }

  internal fun findByHost(peerHost: String): TrustManagerWrapperAndroid {
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
    val trustManager = findByHost(host)

    return trustManager.checkServerTrusted(chain, authType, host)
  }

  override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String) {
    default.checkServerTrusted(chain, authType)
  }

  override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
    default.checkClientTrusted(chain, authType)
  }

  override fun getAcceptedIssuers(): Array<X509Certificate> = default.acceptedIssuers
}
