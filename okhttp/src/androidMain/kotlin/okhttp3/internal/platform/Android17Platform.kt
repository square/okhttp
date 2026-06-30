/*
 * Copyright (c) 2026 OkHttp Authors
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
import android.net.DnsResolver
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
import okhttp3.AsyncDns
import okhttp3.AsyncDns.Companion.asBlocking
import okhttp3.Call
import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.android.AndroidAsyncDns
import okhttp3.ech.EchModeConfiguration
import okhttp3.internal.SuppressSignatureCheck
import okhttp3.internal.platform.AndroidPlatform.Companion.Tag
import okhttp3.internal.platform.android.Android10SocketAdapter
import okhttp3.internal.platform.android.Android17SocketAdapter
import okhttp3.internal.platform.android.AndroidCertificateChainCleaner
import okhttp3.internal.platform.android.AndroidEchModeConfiguration
import okhttp3.internal.platform.android.AndroidSocketAdapter
import okhttp3.internal.platform.android.BouncyCastleSocketAdapter
import okhttp3.internal.platform.android.ConscryptSocketAdapter
import okhttp3.internal.platform.android.DeferredSocketAdapter
import okhttp3.internal.tls.CertificateChainCleaner
import okhttp3.internal.tls.TrustRootIndex

/**
 * Android 17+ (API 37+).
 *
 * This platform uses the post-API 36 Android TLS and DNS APIs directly, including domain
 * encryption policy, HTTPS/SVCB DNS records from [DnsResolver], and Encrypted Client Hello (ECH)
 * configuration on TLS sockets.
 */
@SuppressSignatureCheck
class Android17Platform
  @RequiresApi(37)
  internal constructor() :
  Platform(),
    ContextAwarePlatform {
    override var applicationContext: Context? = null

    private val socketAdapters =
      listOfNotNull(
        Android17SocketAdapter.buildIfSupported(),
        Android10SocketAdapter.buildIfSupported(),
        DeferredSocketAdapter(AndroidSocketAdapter.playProviderFactory),
        DeferredSocketAdapter(ConscryptSocketAdapter.factory),
        DeferredSocketAdapter(BouncyCastleSocketAdapter.factory),
      ).filter { it.isSupported() }

    override fun trustManager(sslSocketFactory: SSLSocketFactory): X509TrustManager? =
      socketAdapters
        .find { it.matchesSocketFactory(sslSocketFactory) }
        ?.trustManager(sslSocketFactory)

    override fun newSSLContext(): SSLContext {
      StrictMode.noteSlowCall("newSSLContext")

      return super.newSSLContext()
    }

    override fun buildTrustRootIndex(trustManager: X509TrustManager): TrustRootIndex {
      StrictMode.noteSlowCall("buildTrustRootIndex")

      return super.buildTrustRootIndex(trustManager)
    }

    override fun configureTlsExtensions(
      call: Call?,
      sslSocket: SSLSocket,
      hostname: String?,
      protocols: List<Protocol>,
    ) {
      socketAdapters
        .find { it.matchesSocket(sslSocket) }
        ?.configureTlsExtensions(
          call = call,
          sslSocket = sslSocket,
          hostname = hostname,
          protocols = protocols,
        )
    }

    override fun getSelectedProtocol(sslSocket: SSLSocket): String? =
      socketAdapters.find { it.matchesSocket(sslSocket) }?.getSelectedProtocol(sslSocket)

    @RequiresApi(36)
    override fun getStackTraceForCloseable(closer: String): Any = CloseGuard().apply { open(closer) }

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

    @SuppressLint("NewApi")
    internal override val echModeConfiguration: EchModeConfiguration = AndroidEchModeConfiguration()

    override fun buildCertificateChainCleaner(trustManager: X509TrustManager): CertificateChainCleaner =
      AndroidCertificateChainCleaner.buildIfSupported(trustManager) ?: super.buildCertificateChainCleaner(trustManager)

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

    @Suppress("NewApi")
    private val asyncDns by lazy { AndroidAsyncDns() }

    @SuppressLint("NewApi")
    override fun platformDns(): Dns = asyncDns.asBlocking()

    @SuppressLint("NewApi")
    override fun platformAsyncDns(): AsyncDns = asyncDns

    companion object {
      val isSupported: Boolean = (isAndroid && Build.VERSION.SDK_INT >= 37)

      @ChecksSdkIntAtLeast(37)
      fun buildIfSupported(): Platform? = if (isSupported) Android17Platform() else null
    }
  }
