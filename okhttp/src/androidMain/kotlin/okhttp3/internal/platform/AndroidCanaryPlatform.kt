/*
 * Copyright (C) 2016 Square, Inc.
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
package okhttp3.internal.platform

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.StrictMode
import android.security.NetworkSecurityPolicy
import android.util.CloseGuard
import android.util.Log
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Protocol
import okhttp3.internal.SuppressSignatureCheck
import okhttp3.internal.platform.AndroidPlatform.Companion.Tag
import okhttp3.internal.platform.android.AndroidCanarySocketAdapter
import okhttp3.internal.platform.android.AndroidCertificateChainCleaner
import okhttp3.internal.tls.CertificateChainCleaner
import okhttp3.internal.tls.TrustRootIndex

/** Android 10+ (API 29+). */
@SuppressSignatureCheck
class AndroidCanaryPlatform
@RequiresApi(36)
internal constructor() :
  Platform(),
  ContextAwarePlatform {
    init {
      println("AndroidCanaryPlatform")
    }

  override var applicationContext: Context? = null

  private val socketAdapter by lazy {
    AndroidCanarySocketAdapter.buildIfSupported()!!
  }

  override fun trustManager(sslSocketFactory: SSLSocketFactory): X509TrustManager? =
    socketAdapter.trustManager(sslSocketFactory)

  override fun newSSLContext(): SSLContext {
    StrictMode.noteSlowCall("newSSLContext")

    return super.newSSLContext()
  }

  override fun buildTrustRootIndex(trustManager: X509TrustManager): TrustRootIndex {
    StrictMode.noteSlowCall("buildTrustRootIndex")

    return super.buildTrustRootIndex(trustManager)
  }

  override fun configureTlsExtensions(
    sslSocket: SSLSocket,
    hostname: String?,
    protocols: List<Protocol>,
  ) {
    socketAdapter.configureTlsExtensions(sslSocket, hostname, protocols)
  }

  override fun getSelectedProtocol(sslSocket: SSLSocket): String? =
    socketAdapter.getSelectedProtocol(sslSocket)

  @RequiresApi(36)
  override fun getStackTraceForCloseable(closer: String): Any =
    CloseGuard().apply { open(closer) }

  @RequiresApi(36)
  override fun logCloseableLeak(
    message: String,
    stackTrace: Any?,
  ) {
    (stackTrace as CloseGuard).warnIfOpen()
  }

  @SuppressLint("NewApi")
  override fun isCleartextTrafficPermitted(hostname: String): Boolean =
    NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(hostname)

  override fun buildCertificateChainCleaner(trustManager: X509TrustManager): CertificateChainCleaner =
    AndroidCertificateChainCleaner.buildIfSupported(trustManager)!!

  override fun log(
    message: String,
    level: Int,
    t: Throwable?,
  ) {
    if (level == WARN) {
      Log.w(Tag, message, t)
    } else {
      Log.i(Tag, message, t)
    }
  }

  companion object {
    val isSupported: Boolean = isAndroid && Build.VERSION.SDK_INT >= 36

    @ChecksSdkIntAtLeast(36)
    fun buildIfSupported(): Platform? = if (isSupported) AndroidCanaryPlatform() else null
  }
}
