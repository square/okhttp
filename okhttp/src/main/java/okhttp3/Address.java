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
package okhttp3;

import java.net.Proxy;
import java.net.ProxySelector;
import java.util.List;
import javax.net.SocketFactory;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.internal.Util;

import static okhttp3.internal.Util.equal;

/**
 * A specification for a connection to an origin server. For simple connections, this is the
 * server's hostname and port. If an explicit proxy is requested (or {@linkplain Proxy#NO_PROXY no
 * proxy} is explicitly requested), this also includes that proxy information. For secure
 * connections the address also includes the SSL socket factory, hostname verifier, and certificate
 * pinner.
 *
 * <p>HTTP requests that share the same {@code Address} may also share the same {@link Connection}.
 */
public final class Address {
  final HttpUrl url;
  final Dns dns;
  final SocketFactory socketFactory;
  final Authenticator proxyAuthenticator;
  final List<Protocol> protocols;
  final List<ConnectionSpec> connectionSpecs;
  final ProxySelector proxySelector;
  final Proxy proxy;
  final SSLSocketFactory sslSocketFactory;
  final HostnameVerifier hostnameVerifier;
  final CertificatePinner certificatePinner;

  public Address(String uriHost, int uriPort, Dns dns, SocketFactory socketFactory,
      SSLSocketFactory sslSocketFactory, HostnameVerifier hostnameVerifier,
      CertificatePinner certificatePinner, Authenticator proxyAuthenticator, Proxy proxy,
      List<Protocol> protocols, List<ConnectionSpec> connectionSpecs, ProxySelector proxySelector) {
    this.url = new HttpUrl.Builder()
        .scheme(sslSocketFactory != null ? "https" : "http")
        .host(uriHost)
        .port(uriPort)
        .build();

    if (dns == null) throw new NullPointerException("dns == null");
    this.dns = dns;

    if (socketFactory == null) throw new NullPointerException("socketFactory == null");
    this.socketFactory = socketFactory;

    if (proxyAuthenticator == null) {
      throw new NullPointerException("proxyAuthenticator == null");
    }
    this.proxyAuthenticator = proxyAuthenticator;

    if (protocols == null) throw new NullPointerException("protocols == null");
    this.protocols = Util.immutableList(protocols);

    if (connectionSpecs == null) throw new NullPointerException("connectionSpecs == null");
    this.connectionSpecs = Util.immutableList(connectionSpecs);

    if (proxySelector == null) throw new NullPointerException("proxySelector == null");
    this.proxySelector = proxySelector;

    this.proxy = proxy;
    this.sslSocketFactory = sslSocketFactory;
    this.hostnameVerifier = hostnameVerifier;
    this.certificatePinner = certificatePinner;
  }

  /**
   * Returns a URL with the hostname and port of the origin server. The path, query, and fragment of
   * this URL are always empty, since they are not significant for planning a route.
   */
  public HttpUrl url() {
    return url;
  }

  /** Returns the service that will be used to resolve IP addresses for hostnames. */
  public Dns dns() {
    return dns;
  }

  /** Returns the socket factory for new connections. */
  public SocketFactory socketFactory() {
    return socketFactory;
  }

  /** Returns the client's proxy authenticator. */
  public Authenticator proxyAuthenticator() {
    return proxyAuthenticator;
  }

  /**
   * Returns the protocols the client supports. This method always returns a non-null list that
   * contains minimally {@link Protocol#HTTP_1_1}.
   */
  public List<Protocol> protocols() {
    return protocols;
  }

  public List<ConnectionSpec> connectionSpecs() {
    return connectionSpecs;
  }

  /**
   * Returns this address's proxy selector. Only used if the proxy is null. If none of this
   * selector's proxies are reachable, a direct connection will be attempted.
   */
  public ProxySelector proxySelector() {
    return proxySelector;
  }

  /**
   * Returns this address's explicitly-specified HTTP proxy, or null to delegate to the {@linkplain
   * #proxySelector proxy selector}.
   */
  public Proxy proxy() {
    return proxy;
  }

  /** Returns the SSL socket factory, or null if this is not an HTTPS address. */
  public SSLSocketFactory sslSocketFactory() {
    return sslSocketFactory;
  }

  /** Returns the hostname verifier, or null if this is not an HTTPS address. */
  public HostnameVerifier hostnameVerifier() {
    return hostnameVerifier;
  }

  /** Returns this address's certificate pinner, or null if this is not an HTTPS address. */
  public CertificatePinner certificatePinner() {
    return certificatePinner;
  }

  @Override public boolean equals(Object other) {
    if (other instanceof Address) {
      Address that = (Address) other;
      return this.url.equals(that.url)
          && this.dns.equals(that.dns)
          && this.proxyAuthenticator.equals(that.proxyAuthenticator)
          && this.protocols.equals(that.protocols)
          && this.connectionSpecs.equals(that.connectionSpecs)
          && this.proxySelector.equals(that.proxySelector)
          && equal(this.proxy, that.proxy)
          && equal(this.sslSocketFactory, that.sslSocketFactory)
          && equal(this.hostnameVerifier, that.hostnameVerifier)
          && equal(this.certificatePinner, that.certificatePinner);
    }
    return false;
  }

  @Override public int hashCode() {
    int result = 17;
    result = 31 * result + url.hashCode();
    result = 31 * result + dns.hashCode();
    result = 31 * result + proxyAuthenticator.hashCode();
    result = 31 * result + protocols.hashCode();
    result = 31 * result + connectionSpecs.hashCode();
    result = 31 * result + proxySelector.hashCode();
    result = 31 * result + (proxy != null ? proxy.hashCode() : 0);
    result = 31 * result + (sslSocketFactory != null ? sslSocketFactory.hashCode() : 0);
    result = 31 * result + (hostnameVerifier != null ? hostnameVerifier.hashCode() : 0);
    result = 31 * result + (certificatePinner != null ? certificatePinner.hashCode() : 0);
    return result;
  }
}
