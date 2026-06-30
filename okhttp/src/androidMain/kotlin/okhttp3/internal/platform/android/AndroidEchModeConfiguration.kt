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
package okhttp3.internal.platform.android

import android.annotation.SuppressLint
import android.net.ssl.EchConfigList
import android.net.ssl.EchConfigMismatchException
import android.net.ssl.InvalidEchDataException
import android.net.ssl.SSLSockets
import android.security.NetworkSecurityPolicy
import androidx.annotation.RequiresApi
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import okhttp3.ech.EchConfig
import okhttp3.ech.EchMode
import okhttp3.ech.EchModeConfiguration
import okio.IOException

/**
 * Android implementation of [EchModeConfiguration] for API 37+.
 *
 * This bridges OkHttp's platform-neutral ECH policy to Android's native ECH APIs:
 * [NetworkSecurityPolicy] supplies the per-host domain encryption policy, the resolved
 * [EchConfig] carries the HTTPS/SVCB ECH configuration list, and [SSLSockets] applies it to the
 * TLS socket.
 */
@RequiresApi(37)
internal class AndroidEchModeConfiguration : EchModeConfiguration {
  @Suppress("NewApi")
  override fun echMode(host: String): EchMode {
    val domainEncryptionMode = NetworkSecurityPolicy.getInstance().getDomainEncryptionMode(host)
    return EchMode.fromNetworkSecurityPolicy(domainEncryptionMode)
  }

  @SuppressLint("NewApi")
  override fun isEchConfigError(e: SSLException): Boolean = e is EchConfigMismatchException

  @Suppress("NewApi")
  override fun applyEch(
    sslSocket: SSLSocket,
    echMode: EchMode,
    host: String,
    echConfig: EchConfig?,
  ) {
    val echConfigList =
      echConfig?.let {
        try {
          EchConfigList.fromBytes(it.config.toByteArray())
        } catch (e: InvalidEchDataException) {
          null
        }
      }

    if (echConfigList != null) {
      SSLSockets.setEchConfigList(sslSocket, echConfigList)
    } else if (echMode.require) {
      throw IOException("Unable to apply required ECH config for $host")
    }
  }
}

internal fun EchMode.Companion.fromNetworkSecurityPolicy(domainEncryptionMode: Int): EchMode =
  when (domainEncryptionMode) {
    NetworkSecurityPolicy.DOMAIN_ENCRYPTION_MODE_OPPORTUNISTIC -> EchMode.Opportunistic
    NetworkSecurityPolicy.DOMAIN_ENCRYPTION_MODE_ENABLED -> EchMode.Strict
    NetworkSecurityPolicy.DOMAIN_ENCRYPTION_MODE_DISABLED -> EchMode.Disabled
    else -> EchMode.Unspecified
  }
