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

import android.annotation.SuppressLint
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.internal.platform.Platform
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

object TrustManagerBridge {
  class Builder(var default: X509TrustManager? = null) {
    private var overrides: MutableList<TrustManagerOverride> = mutableListOf()

    fun default(default: X509TrustManager) = apply {
      this.default = default
    }

    fun hostOverride(hostName: String, trustManager: X509TrustManager) = apply {
      this.overrides.add(TrustManagerOverride({ it == hostName }, null, trustManager))
    }

    fun insecure(hostName: String) = apply {
      this.overrides.add(TrustManagerOverride({ it == hostName }, OkHostnameVerifier.Insecure, InsecureTrustManager))
    }

    fun addOverrides(overrides: List<TrustManagerOverride>) = apply {
      this.overrides.addAll(overrides)
    }

    @IgnoreJRERequirement
    @SuppressLint("NewApi")
    fun build(): X509TrustManager {
      val defaultTrustManager = default ?: Platform.get().platformTrustManager()

      return if (Platform.get().isAndroid) {
        TrustManagerAndroid(defaultTrustManager, overrides.toList())
      } else {
        TrustManagerJvm(defaultTrustManager as X509ExtendedTrustManager, overrides.toList())
      }
    }
  }
}
