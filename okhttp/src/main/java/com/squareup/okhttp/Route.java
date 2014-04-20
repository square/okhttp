/*
 * Copyright (C) 2013 Square, Inc.
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

import com.squareup.okhttp.internal.http.RouteSelector;
import java.net.InetSocketAddress;
import java.net.Proxy;

/**
 * The concrete route used by a connection to reach an abstract origin server.
 * When creating a connection the client has many options:
 * <ul>
 *   <li><strong>HTTP proxy:</strong> a proxy server may be explicitly
 *       configured for the client. Otherwise the {@linkplain java.net.ProxySelector
 *       proxy selector} is used. It may return multiple proxies to attempt.
 *   <li><strong>IP address:</strong> whether connecting directly to an origin
 *       server or a proxy, opening a socket requires an IP address. The DNS
 *       server may return multiple IP addresses to attempt.
 *   <li><strong>TLS version:</strong> which TLS version to attempt with the
 *       HTTPS connection.
 * </ul>
 * Each route is a specific selection of these options.
 */
public final class Route {
  final Address address;
  final Proxy proxy;
  final InetSocketAddress inetSocketAddress;
  final String tlsVersion;

  public Route(Address address, Proxy proxy, InetSocketAddress inetSocketAddress,
      String tlsVersion) {
    if (address == null) throw new NullPointerException("address == null");
    if (proxy == null) throw new NullPointerException("proxy == null");
    if (inetSocketAddress == null) throw new NullPointerException("inetSocketAddress == null");
    if (tlsVersion == null) throw new NullPointerException("tlsVersion == null");
    this.address = address;
    this.proxy = proxy;
    this.inetSocketAddress = inetSocketAddress;
    this.tlsVersion = tlsVersion;
  }

  public Address getAddress() {
    return address;
  }

  /**
   * Returns the {@link Proxy} of this route.
   *
   * <strong>Warning:</strong> This may disagree with {@link Address#getProxy}
   * is null. When the address's proxy is null, the proxy selector will be used.
   */
  public Proxy getProxy() {
    return proxy;
  }

  public InetSocketAddress getSocketAddress() {
    return inetSocketAddress;
  }

  public String getTlsVersion() {
    return tlsVersion;
  }

  boolean supportsNpn() {
    return !tlsVersion.equals(RouteSelector.SSL_V3);
  }

  @Override public boolean equals(Object obj) {
    if (obj instanceof Route) {
      Route other = (Route) obj;
      return address.equals(other.address)
          && proxy.equals(other.proxy)
          && inetSocketAddress.equals(other.inetSocketAddress)
          && tlsVersion.equals(other.tlsVersion);
    }
    return false;
  }

  @Override public int hashCode() {
    int result = 17;
    result = 31 * result + address.hashCode();
    result = 31 * result + proxy.hashCode();
    result = 31 * result + inetSocketAddress.hashCode();
    result = 31 * result + tlsVersion.hashCode();
    return result;
  }
}
