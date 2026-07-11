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
package okhttp3.dns

import java.net.InetAddress
import okio.ByteString
import okio.IOException

interface Dns2 {
  /** Recursively resolve [request] to learn IP addresses and service metadata to connect. */
  fun newCall(request: DnsRequest): DnsCall
}

interface DnsCall {
  val request: DnsRequest
  fun enqueue(callback: DnsCallback)
  fun cancel()
  fun isCanceled(): Boolean
}

interface DnsCallback {
  /**
   * @param last true if this is the last list of records for this address. That is a terminal event
   *   and no further calls to this callback will be made.
   * @param records a possibly-empty set of records received from the name server.
   */
  fun onRecords(call: DnsCall, last: Boolean, records: List<DnsRecord>)

  /**
   * This is a terminal event, and no further calls to this callback will be made.
   */
  fun onFailure(call: DnsCall, e: IOException)
}

// TODO: no data classes in public APIs
data class DnsRequest(
  /**
   * The host from a URL like `www.publicobject.com` or `ietf.org`. It may also be a
   * Punycode-encoded name like `xn--n3h.net`.
   *
   * Hostnames must satisfy the following constraints:
   *
   *  * All characters must be printable ASCII characters.
   *  * These characters are forbidden: `#`, `%`, `/`, `:`, `?`, `@`, `[`, `\`, and `]`.
   *  * The string length must be in [1..253].
   *  * After splitting the string into `.`-separated labels, each label must be in [1..63]. A
   *    single trailing `.` is permitted.
   *
   * Hostnames that don't satisfy these requirements will fail with [DnsCallback.onFailure].
   */
  val hostname: String,

  /**
   * The SVCB protocol name.
   *
   * This should be "https" for both "http://" and "https://" URLs, in order to fetch
   * [DnsRecord.Svcb] records.
   */
  val protocol: String? = null,

  /**
   * The port to query for record types that support it, or -1 for [protocol]'s default port.
   *
   * In an SVCB DNS query, the protocol, port, and hostname are combined like
   * `_8020._ftp.api.example.com`. The protocol and port segments are omitted when they hold
   * default values. This scheme is called [AttrLeaf](https://www.rfc-editor.org/info/rfc8552/).
   */
  val port: Int = -1,
)

sealed class DnsRecord private constructor() {
  /**
   * The hostname that this record applies to.
   *
   * A single hostname (`api.example.com`) may be served by multiple supporting servers. For
   * example, this is useful for geographic distribution (`us-east.api.example.com` and
   * `eu-central.api.example.com`), and for change management (`blue.api.example.com`,
   * `green.api.example.com`).
   *
   * This field will be different from [DnsRequest.hostname] if it is served by alternate hostname,
   * as above. This will be that original hostname if no alternate hostnames are in use, or if the
   * underlying DNS resolver doesn't expose that data.
   */
  abstract val hostname: String

  /**
   * An IPv4 or IPv6 address for this host.
   *
   * (TYPE_A, 0x0001).
   * (TYPE_AAAA, 0x001c).
   */
  // TODO: no data classes in public APIs
  data class IpAddress(
    override val hostname: String,
    val address: InetAddress,
  ) : DnsRecord()

  /**
   * Service metadata.
   *
   * This may apply to all supporting servers, or to a subset of them. If multiple hostnames are
   * in use (as in the `us-east.api.example.com` example above), those hostnames must be themselves
   * resolved to get the IP addresses to connect to. If [Svcb.ipAddressHints] is non-empty, those
   * addresses are available without to an additional DNS round trip. (The additional round trip
   * is still useful for completeness.)
   *
   * If this record is present on the DNS results for an `http://` request, the client should
   * simulate a 307 redirect to the equivalent `https://` URL. From RFC 9460, part 9.5:
   *
   *    If an HTTPS RR query for this "https" URL re RRs or any compatible ServiceMode HTTPS RRs,
   *    the client SHOULD behave as if it has received an HTTP 307 (Temporary Redirect) status code
   *    with this "https" URL in the "Location" field.
   *
   * (TYPE_SVCB, 0x0040).
   * (TYPE_HTTPS, 0x0041).
   */
  // TODO: no data classes in public APIs
  data class Svcb(
    override val hostname: String,

    /**
     * The available ALPN IDs on the target. Use this to select a target that supports the client's
     * available protocols.
     *
     * This value is created by composing two service params. For the "http" protocol, we take the
     * `alpn` value and then add the default "http/1.1" unless `no-default-alpn` is present.
     *
     * (`alpn`, 1)
     * (`no-default-alpn`, 2)
     */
    val alpnIds: List<String>? = null,

    /**
     * The socket should connect to this port, even if the URL has a different port.
     *
     * (`port`, 3)
     */
    val port: Int = -1,

    /**
     * The IP of hostnames addresses known to support this [Svcb] record. If empty, assume all
     * records with the same [DnsRecord.hostname] also support this configuration.
     *
     * (`ipv4hint`, 4)
     * (`ipv6hint`, 6)
     */
    val ipAddressHints: List<InetAddress> = listOf(),

    /**
     * The Encrypted Client Hello (ECH) data. This is encoded according to RFC 9848 and RFC 9849.
     *
     * (`ech`, 5)
     */
    val echConfigList: ByteString? = null,
  ) : DnsRecord()
}
