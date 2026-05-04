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

/**
 * Configures the behavior of Encrypted Client Hello (ECH) for TLS connections.
 */
internal enum class EchMode(
  /** True if OkHttp should attempt to configure ECH for the TLS connection. */
  val attempt: Boolean,
  /** True if the connection must fail when ECH cannot be configured or negotiated. */
  val require: Boolean,
  /** True if OkHttp should retry without ECH when the server rejects the ECH configuration. */
  val fallback: Boolean = false,
) {
  /**
   * The ECH mode is not specified. ECH will not be attempted or required.
   */
  Unspecified(attempt = false, require = false),

  /** ECH is disabled. */
  Disabled(
    attempt = false,
    require = false,
  ),

  /**
   * Attempt ECH if configuration is available, but fall back to standard TLS if it fails.
   */
  Opportunistic(
    attempt = true,
    require = false,
    fallback = true,
  ),

  /**
   * Attempt ECH if the configuration is available.
   */
  Strict(
    attempt = true,
    require = false,
  ),

  /**
   * Attempt ECH and fail the connection if it cannot be established.
   */
  FailClosed(attempt = true, require = true),

  /**
   * Retry with ECH disabled.
   */
  Fallback(attempt = false, require = false),
  ;

  /** Companion for extension functions and Java interop. */
  companion object
}
