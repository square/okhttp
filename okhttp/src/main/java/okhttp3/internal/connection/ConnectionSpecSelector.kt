/*
 * Copyright (C) 2015 Square, Inc.
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

import okhttp3.ConnectionSpec
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.UnknownServiceException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket

/**
 * Handles the connection spec fallback strategy: When a secure socket connection fails due to a
 * handshake / protocol problem the connection may be retried with different protocols. Instances
 * are stateful and should be created and used for a single connection attempt.
 */
internal class ConnectionSpecSelector(
  private val connectionSpecs: List<ConnectionSpec>
) {
  private var nextModeIndex: Int = 0
  private var isFallbackPossible: Boolean = false
  private var isFallback: Boolean = false

  /**
   * Configures the supplied [SSLSocket] to connect to the specified host using an appropriate
   * [ConnectionSpec]. Returns the chosen [ConnectionSpec], never null.
   *
   * @throws IOException if the socket does not support any of the TLS modes available
   */
  @Throws(IOException::class)
  fun configureSecureSocket(sslSocket: SSLSocket): ConnectionSpec {
    var tlsConfiguration: ConnectionSpec? = null
    for (i in nextModeIndex until connectionSpecs.size) {
      val connectionSpec = connectionSpecs[i]
      if (connectionSpec.isCompatible(sslSocket)) {
        tlsConfiguration = connectionSpec
        nextModeIndex = i + 1
        break
      }
    }

    if (tlsConfiguration == null) {
      // This may be the first time a connection has been attempted and the socket does not support
      // any the required protocols, or it may be a retry (but this socket supports fewer protocols
      // than was suggested by a prior socket).
      throw UnknownServiceException("Unable to find acceptable protocols. isFallback=$isFallback," +
          " modes=$connectionSpecs," +
          " supported protocols=${sslSocket.enabledProtocols!!.contentToString()}")
    }

    isFallbackPossible = isFallbackPossible(sslSocket)

    tlsConfiguration.apply(sslSocket, isFallback)

    return tlsConfiguration
  }

  /**
   * Reports a failure to complete a connection. Determines the next [ConnectionSpec] to try,
   * if any.
   *
   * @return true if the connection should be retried using [configureSecureSocket].
   */
  fun connectionFailed(e: IOException): Boolean {
    // Any future attempt to connect using this strategy will be a fallback attempt.
    isFallback = true

    return when {
      !isFallbackPossible -> false

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

  /**
   * Returns true if any later [ConnectionSpec] in the fallback strategy looks possible based on the
   * supplied [SSLSocket]. It assumes that a future socket will have the same capabilities as the
   * supplied socket.
   */
  private fun isFallbackPossible(socket: SSLSocket): Boolean {
    for (i in nextModeIndex until connectionSpecs.size) {
      if (connectionSpecs[i].isCompatible(socket)) {
        return true
      }
    }
    return false
  }
}
