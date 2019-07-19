/*
 * Copyright (C) 2012 The Android Open Source Project
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

import okhttp3.internal.toImmutableList
import java.net.Proxy
import java.net.ProxySelector
import java.util.Objects
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocketFactory

/**
 * A specification for a connection to an origin server. For simple connections, this is the
 * server's hostname and port. If an explicit proxy is requested (or [no proxy][Proxy.NO_PROXY] is explicitly requested),
 * this also includes that proxy information. For secure connections the address also includes the SSL socket factory,
 * hostname verifier, and certificate pinner.
 *
 * HTTP requests that share the same [Address] may also share the same [Connection].
 */
class Address(
  uriHost: String,
  uriPort: Int,
  /** Returns the service that will be used to resolve IP addresses for hostnames. */
  @get:JvmName("dns") val dns: Dns,

  /** Returns the socket factory for new connections. */
  @get:JvmName("socketFactory") val socketFactory: SocketFactory,

  /** Returns the SSL socket factory, or null if this is not an HTTPS address. */
  @get:JvmName("sslSocketFactory") val sslSocketFactory: SSLSocketFactory?,

  /** Returns the hostname verifier, or null if this is not an HTTPS address. */
  @get:JvmName("hostnameVerifier") val hostnameVerifier: HostnameVerifier?,

  /** Returns this address's certificate pinner, or null if this is not an HTTPS address. */
  @get:JvmName("certificatePinner") val certificatePinner: CertificatePinner?,

  /** Returns the client's proxy authenticator. */
  @get:JvmName("proxyAuthenticator") val proxyAuthenticator: Authenticator,

  /**
   * Returns this address's explicitly-specified HTTP proxy, or null to delegate to the {@linkplain
   * #proxySelector proxy selector}.
   */
  @get:JvmName("proxy") val proxy: Proxy?,

  protocols: List<Protocol>,
  connectionSpecs: List<ConnectionSpec>,

  /**
   * Returns this address's proxy selector. Only used if the proxy is null. If none of this
   * selector's proxies are reachable, a direct connection will be attempted.
   */
  @get:JvmName("proxySelector") val proxySelector: ProxySelector
) {
  /**
   * Returns a URL with the hostname and port of the origin server. The path, query, and fragment of
   * this URL are always empty, since they are not significant for planning a route.
   */
  @get:JvmName("url") val url: HttpUrl = HttpUrl.Builder()
      .scheme(if (sslSocketFactory != null) "https" else "http")
      .host(uriHost)
      .port(uriPort)
      .build()

  /**
   * The protocols the client supports. This method always returns a non-null list that
   * contains minimally [Protocol.HTTP_1_1].
   */
  @get:JvmName("protocols") val protocols: List<Protocol> = protocols.toImmutableList()

  @get:JvmName("connectionSpecs") val connectionSpecs: List<ConnectionSpec> =
      connectionSpecs.toImmutableList()

  @JvmName("-deprecated_url")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "url"),
      level = DeprecationLevel.ERROR)
  fun url() = url

  @JvmName("-deprecated_dns")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "dns"),
      level = DeprecationLevel.ERROR)
  fun dns() = dns

  @JvmName("-deprecated_socketFactory")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "socketFactory"),
      level = DeprecationLevel.ERROR)
  fun socketFactory() = socketFactory

  @JvmName("-deprecated_proxyAuthenticator")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "proxyAuthenticator"),
      level = DeprecationLevel.ERROR)
  fun proxyAuthenticator() = proxyAuthenticator

  @JvmName("-deprecated_protocols")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "protocols"),
      level = DeprecationLevel.ERROR)
  fun protocols() = protocols

  @JvmName("-deprecated_connectionSpecs")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "connectionSpecs"),
      level = DeprecationLevel.ERROR)
  fun connectionSpecs() = connectionSpecs

  @JvmName("-deprecated_proxySelector")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "proxySelector"),
      level = DeprecationLevel.ERROR)
  fun proxySelector() = proxySelector

  @JvmName("-deprecated_proxy")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "proxy"),
      level = DeprecationLevel.ERROR)
  fun proxy() = proxy

  @JvmName("-deprecated_sslSocketFactory")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "sslSocketFactory"),
      level = DeprecationLevel.ERROR)
  fun sslSocketFactory() = sslSocketFactory

  @JvmName("-deprecated_hostnameVerifier")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "hostnameVerifier"),
      level = DeprecationLevel.ERROR)
  fun hostnameVerifier() = hostnameVerifier

  @JvmName("-deprecated_certificatePinner")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "certificatePinner"),
      level = DeprecationLevel.ERROR)
  fun certificatePinner() = certificatePinner

  override fun equals(other: Any?): Boolean {
    return other is Address &&
        url == other.url &&
        equalsNonHost(other)
  }

  override fun hashCode(): Int {
    var result = 17
    result = 31 * result + url.hashCode()
    result = 31 * result + dns.hashCode()
    result = 31 * result + proxyAuthenticator.hashCode()
    result = 31 * result + protocols.hashCode()
    result = 31 * result + connectionSpecs.hashCode()
    result = 31 * result + proxySelector.hashCode()
    result = 31 * result + Objects.hashCode(proxy)
    result = 31 * result + Objects.hashCode(sslSocketFactory)
    result = 31 * result + Objects.hashCode(hostnameVerifier)
    result = 31 * result + Objects.hashCode(certificatePinner)
    return result
  }

  internal fun equalsNonHost(that: Address): Boolean {
    return this.dns == that.dns &&
        this.proxyAuthenticator == that.proxyAuthenticator &&
        this.protocols == that.protocols &&
        this.connectionSpecs == that.connectionSpecs &&
        this.proxySelector == that.proxySelector &&
        this.proxy == that.proxy &&
        this.sslSocketFactory == that.sslSocketFactory &&
        this.hostnameVerifier == that.hostnameVerifier &&
        this.certificatePinner == that.certificatePinner &&
        this.url.port == that.url.port
  }

  override fun toString(): String {
    return "Address{" +
        "${url.host}:${url.port}, " +
        (if (proxy != null) "proxy=$proxy" else "proxySelector=$proxySelector") +
        "}"
  }
}
