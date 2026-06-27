/*
 * Copyright (C) 2026 Square, Inc.
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
@file:JvmName("NamedGroups")

package okhttp3.internal.platform

import javax.net.ssl.SSLSocket

/**
 * Applies the TLS 1.3 named groups (`supported_groups`) to [sslSocket].
 *
 * This is the base implementation, used on Android and on Java runtimes older than 20: it is a
 * no-op, because [javax.net.ssl.SSLParameters.setNamedGroups] was only added in Java 20.
 *
 * On Java 20+ this class is replaced at runtime by the multi-release variant packaged under
 * `META-INF/versions/20/`, which calls `SSLParameters.setNamedGroups` directly. Keeping both
 * implementations behind the same class name means the call sites (in [okhttp3.ConnectionSpec] and
 * MockWebServer) need no reflection and no version checks.
 *
 * This lives in the `okhttp3.internal` package — public so other OkHttp modules can reuse it, but
 * excluded from the published API surface.
 */
@JvmName("applyNamedGroups")
fun applyNamedGroups(
  sslSocket: SSLSocket,
  namedGroups: Array<String>,
) {
  // No-op: this platform predates SSLParameters.setNamedGroups.
}
