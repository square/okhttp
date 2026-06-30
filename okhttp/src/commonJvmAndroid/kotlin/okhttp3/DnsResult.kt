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
package okhttp3

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import okio.ByteString

/**
 * A single result of a DNS query.
 *
 * Results are carried by value through [AsyncDns.DnsCallback], so a [Dns] or [AsyncDns] that
 * decorates another resolver and forwards its results preserves all of this data automatically.
 * This is intentional: connection metadata such as Encrypted Client Hello (ECH) must not be lost
 * when a resolver is wrapped.
 */
internal sealed interface DnsResult {
  /** A resolved IP address from an `A` or `AAAA` record. This is the authoritative address source. */
  class Address(
    val address: InetAddress,
  ) : DnsResult

  /**
   * An HTTPS (SVCB) service record for the host, as defined by
   * [RFC 9460](https://www.rfc-editor.org/rfc/rfc9460.html).
   *
   * A record is in ServiceMode when [svcPriority] is greater than 0 and AliasMode when it is 0.
   * Address hints ([ipv4Hints]/[ipv6Hints]) are an optimization only; `A`/`AAAA` records remain
   * the authoritative address source (RFC 9460 §7.3).
   */
  class HttpsService(
    /** The serialized ECHConfigList for this endpoint, or null if the record carries no ECH. */
    val ech: ByteString? = null,
    /** SvcPriority; 0 selects AliasMode, any other value selects ServiceMode. */
    val svcPriority: Int = 1,
    /** The TargetName the record points at, or the empty string for the origin host ("."). */
    val targetName: String = "",
    /** ALPN protocol identifiers advertised by the `alpn` SvcParam. */
    val alpn: List<String> = listOf(),
    /** The `port` SvcParam, or null when absent. */
    val port: Int? = null,
    /** IPv4 address hints (`ipv4hint` SvcParam). */
    val ipv4Hints: List<Inet4Address> = listOf(),
    /** IPv6 address hints (`ipv6hint` SvcParam). */
    val ipv6Hints: List<Inet6Address> = listOf(),
  ) : DnsResult
}
