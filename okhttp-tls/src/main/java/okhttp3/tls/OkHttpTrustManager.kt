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
package okhttp3.tls

import android.annotation.SuppressLint
import java.security.cert.X509Certificate
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.internal.platform.Platform
import okhttp3.tls.internal.OkHttpTrustManagerAndroid
import okhttp3.tls.internal.OkHttpTrustManagerJvm
import okhttp3.tls.internal.TrustManagerOverride
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

object InsecureTrustManager : X509TrustManager {
  override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

  override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}

  override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

object OkHttpTrustManager {
  class Builder(var delegate: X509TrustManager) {
    private var overrides: MutableList<TrustManagerOverride> = mutableListOf()

    fun delegate(delegate: X509TrustManager) = apply {
      this.delegate = delegate
    }

    fun hostOverride(hostName: String, trustManager: X509TrustManager) = apply {
      this.overrides.add(
          TrustManagerOverride({ it == hostName }, trustManager))
    }

    fun insecure(hostName: String) = apply {
      this.overrides.add(TrustManagerOverride({ it == hostName },
          InsecureTrustManager))
    }

    fun hostCertificates(hostName: String, handshakeCertificates: HandshakeCertificates) = apply {
      TODO("what should the API look like for jumping from HandshakeCertificates")
    }

    @IgnoreJRERequirement
    @SuppressLint("NewApi")
    fun build(): X509TrustManager {
      return if (Platform.get().isAndroid) {
        OkHttpTrustManagerAndroid(delegate, overrides.toList())
      } else {
        OkHttpTrustManagerJvm(
            delegate as X509ExtendedTrustManager, overrides.toList())
      }
    }
  }
}
