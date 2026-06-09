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
package okhttp3.ech

import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import okhttp3.Dns

/**
 * Configuration and management for Encrypted Client Hello (ECH).
 *
 * This interface provides the mechanism to determine the ECH strategy for a given host,
 * apply ECH parameters to an [SSLSocket], and identify ECH-specific connection failures.
 */
internal interface EchModeConfiguration {
  /**
   * Determines the [EchMode] strategy to be used for the specified [host].
   *
   * @param host the hostname for which the ECH strategy is requested.
   * @return the [EchMode] to be applied during the connection process.
   */
  fun echMode(host: String): EchMode

  /**
   * Configures [sslSocket] with Encrypted Client Hello (ECH) parameters for [host].
   *
   * Implementations may use [dns] to retrieve ECH configuration records. If [echMode] requires
   * ECH and no configuration can be applied, this should throw an [java.io.IOException]. Returns
   * the configuration that was applied, or null when no ECH configuration was used.
   */
  fun applyEch(
    sslSocket: SSLSocket,
    echMode: EchMode,
    host: String,
    dns: Dns,
  ): EchConfig?

  /**
   * Returns true if [e] indicates a failure due to an invalid or expired ECH configuration.
   *
   * This typically occurs when the server's ECH public key has rotated. When this returns
   * true, the client may use the server-provided "retry_config" to update its configuration
   * and attempt the connection again.
   *
   * @param e the exception thrown during the SSL handshake.
   */
  fun isEchConfigError(e: SSLException): Boolean = false

  /** Built-in [EchModeConfiguration] instances. */
  companion object {
    /**
     * A default implementation of [EchModeConfiguration] that performs no ECH-related actions
     * and always returns [EchMode.Unspecified].
     */
    val Unspecified =
      object : EchModeConfiguration {
        override fun echMode(host: String): EchMode = EchMode.Unspecified

        override fun applyEch(
          sslSocket: SSLSocket,
          echMode: EchMode,
          host: String,
          dns: Dns,
        ): EchConfig? {
          check(!echMode.attempt)
          return null
        }
      }
  }
}
