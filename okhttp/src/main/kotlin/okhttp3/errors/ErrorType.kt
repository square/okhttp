/* Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.errors

/**
 * Defines the [type](https://wicg.github.io/network-error-logging/#dfn-report-type) of
 * network error described by a NEL report.
 */
class ErrorType private constructor(val type: String) {
  override fun toString(): String {
    return type
  }

  companion object {
    /** The request did not result in a network error.  */
    var OK = other("ok")

    /** DNS server was unreachable.  */
    var DNS_UNREACHABLE = other("dns.unreachable")

    /** DNS server responded but was unable to resolve the address.  */
    var DNS_NAME_NOT_RESOLVED = other("dns.name_not_resolved")

    /** Request to the DNS server failed due to reasons not covered by previous errors.  */
    var DNS_FAILED = other("dns.failed")

    /** TCP connection to the server timed out.  */
    var TCP_TIMED_OUT = other("tcp.timed_out")

    /** The TCP connection was closed by the server.  */
    var TCP_CLOSED = other("tcp.closed")

    /** The TCP connection was reset.  */
    var TCP_RESET = other("tcp.reset")

    /** The TCP connection was refused by the server.  */
    var TCP_REFUSED = other("tcp.refused")

    /** The TCP connection was aborted.  */
    var TCP_ABORTED = other("tcp.aborted")

    /** The IP address was invalid.  */
    var TCP_ADDRESS_INVALID = other("tcp.address_invalid")

    /** The IP address was unreachable.  */
    var TCP_ADDRESS_UNREACHABLE = other("tcp.address_unreachable")

    /** The TCP connection failed due to reasons not covered by previous errors.  */
    var TCP_FAILED = other("tcp.failed")

    /** The TLS connection was aborted due to version or cipher mismatch.  */
    var TLS_VERSION_OR_CIPHER_MISMATCH = other("tls.version_or_cipher_mismatch")

    /** The TLS connection was aborted due to invalid client certificate.  */
    var TLS_BAD_CLIENT_AUTH_CERT = other("tls.bad_client_auth_cert")

    /** The TLS connection was aborted due to invalid name.  */
    var TLS_CERT_NAME_INVALID = other("tls.cert.name_invalid")

    /** The TLS connection was aborted due to invalid certificate date.  */
    var TLS_CERT_DATE_INVALID = other("tls.cert.date_invalid")

    /** The TLS connection was aborted due to invalid issuing authority.  */
    var TLS_CERT_AUTHORITY_INVALID = other("tls.cert.authority_invalid")

    /** The TLS connection was aborted due to invalid certificate.  */
    var TLS_CERT_INVALID = other("tls.cert.invalid")

    /** The TLS connection was aborted due to revoked server certificate.  */
    var TLS_CERT_REVOKED = other("tls.cert.revoked")

    /** The TLS connection was aborted due to a key pinning error.  */
    var TLS_CERT_PINNED_KEY_NOT_IN_CERT_CHAIN = other("tls.cert.pinned_key_not_in_cert_chain")

    /** The TLS connection was aborted due to a TLS protocol error.  */
    var TLS_PROTOCOL_ERROR = other("tls.protocol.error")

    /** The TLS connection failed due to reasons not covered by previous errors.  */
    var TLS_FAILED = other("tls.failed")

    /** The connection was aborted due to an HTTP protocol error.  */
    var HTTP_PROTOCOL_ERROR = other("http.protocol.error")

    /**
     * Response was empty, had a content-length mismatch, had improper encoding, and/or other
     * conditions that prevented user agent from processing the response.
     */
    var HTTP_RESPONSE_INVALID = other("http.response.invalid")

    /** The request was aborted due to a detected redirect loop.  */
    var HTTP_RESPONSE_REDIRECT_LOOP = other("http.response.redirect_loop")

    /** The connection failed due to errors in HTTP protocol not covered by previous errors.  */
    var HTTP_FAILED = other("http.failed")

    /** User aborted the resource fetch before it was complete.  */
    var ABANDONED = other("abandoned")

    /** Error type is unknown.  */
    var UNKNOWN = other("unknown")

    /** An error not covered by any of the cases listed in the standard.  */
    fun other(type: String): ErrorType {
      return ErrorType(type)
    }
  }
}