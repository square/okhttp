/*
 * Copyright (C) 2022 Block, Inc.
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
package okhttp3.internal.connection

import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.UnknownServiceException
import java.security.cert.CertificateException
import java.util.Objects
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import okhttp3.ConnectionSpec
import okhttp3.Request
import okio.IOException

/**
 * What to do once we have a socket but before we can make HTTP calls. This is usually a combination
 * of the following features:
 *
 *  * CONNECT tunnels. When using an HTTP proxy to reach an HTTPS server we must send a CONNECT
 *    request, and handle authorization challenges from the proxy.
 *
 *  * TLS handshakes.
 *
 * We might need many attempts at each of these steps. Tunnels fail due to authentication
 * challenges, and TLS handshakes fail due to mismatched protocol versions. When we need another
 * attempt, this class tracks what to try next.
 *
 * Unlike routes, we don't know that we'll need another equip plan until the current one fails.
 */
internal data class EquipPlan(
  val attempt: Int = 0,
  val tunnelRequest: Request? = null,
  val connectionSpecIndex: Int = -1,
  val isTlsFallback: Boolean = false,
) {

  /** Returns this if its [connectionSpecIndex] is defined, or defines it otherwise. */
  @Throws(IOException::class)
  fun withCurrentOrInitialConnectionSpec(
    connectionSpecs: List<ConnectionSpec>,
    sslSocket: SSLSocket
  ): EquipPlan {
    if (connectionSpecIndex != -1) return this
    return nextConnectionSpec(connectionSpecs, sslSocket)
      ?: throw UnknownServiceException(
        "Unable to find acceptable protocols." +
          " isFallback=${isTlsFallback}," +
          " modes=$connectionSpecs," +
          " supported protocols=${sslSocket.enabledProtocols!!.contentToString()}"
      )
  }

  /** Returns the next plan to use after this, or null if no more compatible plans are available. */
  fun nextConnectionSpec(
    connectionSpecs: List<ConnectionSpec>,
    sslSocket: SSLSocket
  ): EquipPlan? {
    for (i in connectionSpecIndex + 1 until connectionSpecs.size) {
      if (connectionSpecs[i].isCompatible(sslSocket)) {
        return copy(connectionSpecIndex = i, isTlsFallback = (connectionSpecIndex != -1))
      }
    }
    return null
  }

  override fun hashCode(): Int {
    var result = 17
    result = 31 * result + attempt
    result = 31 * result + Objects.hashCode(tunnelRequest)
    result = 31 * result + connectionSpecIndex
    result = 31 * result + (if (isTlsFallback) 0 else 1)
    return result
  }
}

/** Returns true if a TLS connection should be retried after [e]. */
fun retryTlsHandshake(e: IOException): Boolean {
  return when {
    // If there was a protocol problem, don't recover.
    e is ProtocolException -> false

    // If there was an interruption or timeout (SocketTimeoutException), don't recover.
    // For the socket connect timeout case we do not try the same host with a different
    // ConnectionSpec: we assume it is unreachable.
    e is InterruptedIOException -> false

    // If the problem was a CertificateException from the X509TrustManager, do not retry.
    e is SSLHandshakeException && e.cause is CertificateException -> false

    // e.g. a certificate pinning error.
    e is SSLPeerUnverifiedException -> false

    // Retry for all other SSL failures.
    e is SSLException -> true

    else -> false
  }
}
