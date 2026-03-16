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
import android.net.ssl.EchConfigMismatchException
import android.os.Build
import android.os.StrictMode
import android.security.NetworkSecurityPolicy
import android.util.CloseGuard
import android.util.Log
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.internal.SuppressSignatureCheck
import okhttp3.internal.platform.AndroidPlatform.Companion.Tag
import okhttp3.internal.platform.android.Android17SocketAdapter
import okhttp3.internal.platform.android.AndroidCertificateChainCleaner
import okhttp3.internal.tls.CertificateChainCleaner
import okhttp3.internal.tls.TrustRootIndex

/** Android 17+ (API 29+). */
@SuppressSignatureCheck
class Android17Platform
@RequiresApi(36)
internal constructor() :
  Platform(),
  ContextAwarePlatform {
  init {
    println("Android17Platform")
  }

  override var applicationContext: Context? = null

  private val socketAdapter by lazy {
    Android17SocketAdapter.buildIfSupported()!!
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

  @Suppress("NewApi")
  @RequiresApi(37)
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

  override val echModeConfiguration: EchModeConfiguration = object : EchModeConfiguration {
    @SuppressLint("NewApi")
    override fun echMode(hostname: String): EchMode {
      return EchMode.fromNetworkSecurityPolicy(
        NetworkSecurityPolicy.getInstance().getDomainEncryptionMode(hostname)
      )
    }

    @SuppressLint("NewApi")
    override fun isEchConfigError(e: SSLException): Boolean {
      return e is EchConfigMismatchException
    }
  }


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



  @RequiresApi(36)
  internal val androidDns = AndroidDnsResolverDns()

  @SuppressLint("NewApi")
  override fun platformDns(): Dns = androidDns

  companion object {
    val isSupported: Boolean = isAndroid && Build.VERSION.SDK_INT >= 36

    @ChecksSdkIntAtLeast(36)
    fun buildIfSupported(): Platform? = if (isSupported) Android17Platform() else null
  }
}

private fun EchMode.Companion.fromNetworkSecurityPolicy(domainEncryptionMode: Int): EchMode {
  return when (domainEncryptionMode) {
    NetworkSecurityPolicy.DOMAIN_ENCRYPTION_MODE_OPPORTUNISTIC -> EchMode.Opportunistic
    NetworkSecurityPolicy.DOMAIN_ENCRYPTION_MODE_ENABLED -> EchMode.Strict
    NetworkSecurityPolicy.DOMAIN_ENCRYPTION_MODE_DISABLED -> EchMode.Disabled
    else -> EchMode.Unspecified
  }
}
