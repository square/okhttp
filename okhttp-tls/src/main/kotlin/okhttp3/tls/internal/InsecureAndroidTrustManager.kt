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

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/** This extends [X509TrustManager] for Android to disable verification for a set of hosts. */
internal class InsecureAndroidTrustManager(
  private val delegate: X509TrustManager,
  private val insecureHosts: List<String>
) : X509TrustManager {
  private val checkServerTrustedMethod: Method? = try {
    delegate::class.java.getMethod("checkServerTrusted",
        Array<X509Certificate>::class.java, String::class.java, String::class.java)
  } catch (_: NoSuchMethodException) {
    null
  }

  /** Android method to clean and sort certificates, called via reflection. */
  @Suppress("unused", "UNCHECKED_CAST")
  fun checkServerTrusted(
    chain: Array<out X509Certificate>,
    authType: String,
    host: String
  ): List<Certificate> {
    if (host in insecureHosts) return listOf()
    try {
      val method = checkServerTrustedMethod
          ?: throw CertificateException("Failed to call checkServerTrusted")
      return method.invoke(delegate, chain, authType, host) as List<Certificate>
    } catch (e: InvocationTargetException) {
      throw e.targetException
    }
  }

  override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

  override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) =
    throw CertificateException("Unsupported operation")

  override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) =
    throw CertificateException("Unsupported operation")
}
