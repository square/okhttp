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
package com.squareup.okhttp;

import com.squareup.okhttp.internal.Util;
import java.net.Proxy;
import java.net.ProxySelector;
import java.util.List;
import javax.net.SocketFactory;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocketFactory;

import static com.squareup.okhttp.internal.Util.equal;

/**
 * A specification for a connection to an origin server. For simple connections,
 * this is the server's hostname and port. If an explicit proxy is requested (or
 * {@linkplain Proxy#NO_PROXY no proxy} is explicitly requested), this also includes
 * that proxy information. For secure connections the address also includes the
 * SSL socket factory, hostname verifier, and certificate pinner.
 *
 * <p>HTTP requests that share the same {@code Address} may also share the same
 * {@link Connection}.
 */
public final class Address {
  final String uriHost;
  final int uriPort;
  final Dns dns;
  final SocketFactory socketFactory;
  final Authenticator authenticator;
  final List<Protocol> protocols;
  final List<ConnectionSpec> connectionSpecs;
  final ProxySelector proxySelector;
  final Proxy proxy;
  final SSLSocketFactory sslSocketFactory;
  final HostnameVerifier hostnameVerifier;
  final Sha1CertificatePinner certificatePinner;

  public Address(String uriHost, int uriPort, Dns dns, SocketFactory socketFactory,
      SSLSocketFactory sslSocketFactory, HostnameVerifier hostnameVerifier,
      Sha1CertificatePinner certificatePinner, Authenticator authenticator, Proxy proxy,
      List<Protocol> protocols, List<ConnectionSpec> connectionSpecs, ProxySelector proxySelector) {
    if (uriHost == null) throw new NullPointerException("uriHost == null");
    this.uriHost = uriHost;

    if (uriPort <= 0) throw new IllegalArgumentException("uriPort <= 0: " + uriPort);
    this.uriPort = uriPort;

    if (dns == null) throw new IllegalArgumentException("dns == null");
    this.dns = dns;

    if (socketFactory == null) throw new IllegalArgumentException("socketFactory == null");
    this.socketFactory = socketFactory;

    if (authenticator == null) throw new IllegalArgumentException("authenticator == null");
    this.authenticator = authenticator;

    if (protocols == null) throw new IllegalArgumentException("protocols == null");
    this.protocols = Util.immutableList(protocols);

    if (connectionSpecs == null) throw new IllegalArgumentException("connectionSpecs == null");
    this.connectionSpecs = Util.immutableList(connectionSpecs);

    if (proxySelector == null) throw new IllegalArgumentException("proxySelector == null");
    this.proxySelector = proxySelector;

    this.proxy = proxy;
    this.sslSocketFactory = sslSocketFactory;
    this.hostnameVerifier = hostnameVerifier;
    this.certificatePinner = certificatePinner;
  }

  /** Returns the hostname of the origin server. */
  public String getUriHost() {
    return uriHost;
  }

  /**
   * Returns the port of the origin server; typically 80 or 443. Unlike
   * may {@code getPort()} accessors, this method never returns -1.
   */
  public int getUriPort() {
    return uriPort;
  }

  /** Returns the service that will be used to resolve IP addresses for hostnames. */
  public Dns getDns() {
    return dns;
  }

  /** Returns the socket factory for new connections. */
  public SocketFactory getSocketFactory() {
    return socketFactory;
  }

  /** Returns the client's authenticator. */
  public Authenticator getAuthenticator() {
    return authenticator;
  }

  /**
   * Returns the protocols the client supports. This method always returns a
   * non-null list that contains minimally {@link Protocol#HTTP_1_1}.
   */
  public List<Protocol> getProtocols() {
    return protocols;
  }

  public List<ConnectionSpec> getConnectionSpecs() {
    return connectionSpecs;
  }

  /**
   * Returns this address's proxy selector. Only used if the proxy is null. If none of this
   * selector's proxies are reachable, a direct connection will be attempted.
   */
  public ProxySelector getProxySelector() {
    return proxySelector;
  }

  /**
   * Returns this address's explicitly-specified HTTP proxy, or null to
   * delegate to the {@linkplain #getProxySelector proxy selector}.
   */
  public Proxy getProxy() {
    return proxy;
  }

  /** Returns the SSL socket factory, or null if this is not an HTTPS address. */
  public SSLSocketFactory getSslSocketFactory() {
    return sslSocketFactory;
  }

  /** Returns the hostname verifier, or null if this is not an HTTPS address. */
  public HostnameVerifier getHostnameVerifier() {
    return hostnameVerifier;
  }

  /** Returns this address's certificate pinner, or null if this is not an HTTPS address. */
  public CertificatePinner getCertificatePinner() {
    return certificatePinner;
  }

  @Override public boolean equals(Object other) {
    if (other instanceof Address) {
      Address that = (Address) other;
      return this.uriHost.equals(that.uriHost)
          && this.uriPort == that.uriPort
          && this.dns.equals(that.dns)
          && this.authenticator.equals(that.authenticator)
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
    result = 31 * result + uriHost.hashCode();
    result = 31 * result + uriPort;
    result = 31 * result + dns.hashCode();
    result = 31 * result + authenticator.hashCode();
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
