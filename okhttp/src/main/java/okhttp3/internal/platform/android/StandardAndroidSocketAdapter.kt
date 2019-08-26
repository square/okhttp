/*
 * Copyright (C) 2019 Square, Inc.
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
package okhttp3.internal.platform.android

import okhttp3.internal.platform.Platform
import okhttp3.internal.readFieldOrNull
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Base Android reflection based SocketAdapter for the built in Android SSLSocket.
 */
class StandardAndroidSocketAdapter(
  sslSocketClass: Class<in SSLSocket>,
  private val sslSocketFactoryClass: Class<in SSLSocketFactory>,
  private val paramClass: Class<*>
) : AndroidSocketAdapter(
    sslSocketClass) {

  override fun matchesSocketFactory(sslSocketFactory: SSLSocketFactory): Boolean =
      sslSocketFactoryClass.isInstance(sslSocketFactory)

  override fun trustManager(sslSocketFactory: SSLSocketFactory): X509TrustManager? {
    val context: Any? =
        readFieldOrNull(sslSocketFactory, paramClass,
            "sslParameters")
    val x509TrustManager = readFieldOrNull(
        context!!, X509TrustManager::class.java, "x509TrustManager")
    return x509TrustManager ?: readFieldOrNull(context,
        X509TrustManager::class.java,
        "trustManager")
  }

  companion object {
    @Suppress("UNCHECKED_CAST")
    fun buildIfSupported(packageName: String = "com.android.org.conscrypt"): SocketAdapter? {
      return try {
        val sslSocketClass = Class.forName("$packageName.OpenSSLSocketImpl") as Class<in SSLSocket>
        val sslSocketFactoryClass =
            Class.forName("$packageName.OpenSSLSocketFactoryImpl") as Class<in SSLSocketFactory>
        val paramsClass = Class.forName("$packageName.SSLParametersImpl")

        StandardAndroidSocketAdapter(sslSocketClass, sslSocketFactoryClass, paramsClass)
      } catch (e: Exception) {
        androidLog(Platform.WARN, "unable to load android socket classes", e)
        null
      }
    }
  }
}
