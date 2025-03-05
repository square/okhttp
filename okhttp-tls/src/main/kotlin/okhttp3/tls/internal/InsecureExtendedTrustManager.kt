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

import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager
import okhttp3.internal.peerName
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

/**
 * This extends [X509ExtendedTrustManager] to disable verification for a set of hosts.
 *
 * Note that the superclass [X509ExtendedTrustManager] isn't available on Android until version 7
 * (API level 24).
 */
@IgnoreJRERequirement
internal class InsecureExtendedTrustManager(
  private val delegate: X509ExtendedTrustManager,
  private val insecureHosts: List<String>,
) : X509ExtendedTrustManager() {
  override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

  override fun checkServerTrusted(
    chain: Array<out X509Certificate>,
    authType: String,
    socket: Socket,
  ) {
    if (socket.peerName() !in insecureHosts) {
      delegate.checkServerTrusted(chain, authType, socket)
    }
  }

  override fun checkServerTrusted(
    chain: Array<out X509Certificate>,
    authType: String,
    engine: SSLEngine,
  ) {
    if (engine.peerHost !in insecureHosts) {
      delegate.checkServerTrusted(chain, authType, engine)
    }
  }

  override fun checkServerTrusted(
    chain: Array<out X509Certificate>,
    authType: String,
  ) = throw CertificateException("Unsupported operation")

  override fun checkClientTrusted(
    chain: Array<out X509Certificate>,
    authType: String?,
  ) = throw CertificateException("Unsupported operation")

  override fun checkClientTrusted(
    chain: Array<out X509Certificate>,
    authType: String,
    engine: SSLEngine?,
  ) = throw CertificateException("Unsupported operation")

  override fun checkClientTrusted(
    chain: Array<out X509Certificate>,
    authType: String,
    socket: Socket?,
  ) = throw CertificateException("Unsupported operation")
}
