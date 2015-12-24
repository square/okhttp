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
package okhttp3;

import java.net.InetSocketAddress;
import java.net.Proxy;

/**
 * The concrete route used by a connection to reach an abstract origin server. When creating a
 * connection the client has many options:
 *
 * <ul>
 *     <li><strong>HTTP proxy:</strong> a proxy server may be explicitly configured for the client.
 *         Otherwise the {@linkplain java.net.ProxySelector proxy selector} is used. It may return
 *         multiple proxies to attempt.
 *     <li><strong>IP address:</strong> whether connecting directly to an origin server or a proxy,
 *         opening a socket requires an IP address. The DNS server may return multiple IP addresses
 *         to attempt.
 * </ul>
 *
 * <p>Each route is a specific selection of these options.
 */
public final class Route {
  final Address address;
  final Proxy proxy;
  final InetSocketAddress inetSocketAddress;

  public Route(Address address, Proxy proxy, InetSocketAddress inetSocketAddress) {
    if (address == null) {
      throw new NullPointerException("address == null");
    }
    if (proxy == null) {
      throw new NullPointerException("proxy == null");
    }
    if (inetSocketAddress == null) {
      throw new NullPointerException("inetSocketAddress == null");
    }
    this.address = address;
    this.proxy = proxy;
    this.inetSocketAddress = inetSocketAddress;
  }

  public Address address() {
    return address;
  }

  /**
   * Returns the {@link Proxy} of this route.
   *
   * <strong>Warning:</strong> This may disagree with {@link Address#proxy} when it is null. When
   * the address's proxy is null, the proxy selector is used.
   */
  public Proxy proxy() {
    return proxy;
  }

  public InetSocketAddress socketAddress() {
    return inetSocketAddress;
  }

  /**
   * Returns true if this route tunnels HTTPS through an HTTP proxy. See <a
   * href="http://www.ietf.org/rfc/rfc2817.txt">RFC 2817, Section 5.2</a>.
   */
  public boolean requiresTunnel() {
    return address.sslSocketFactory != null && proxy.type() == Proxy.Type.HTTP;
  }

  @Override public boolean equals(Object obj) {
    if (obj instanceof Route) {
      Route other = (Route) obj;
      return address.equals(other.address)
          && proxy.equals(other.proxy)
          && inetSocketAddress.equals(other.inetSocketAddress);
    }
    return false;
  }

  @Override public int hashCode() {
    int result = 17;
    result = 31 * result + address.hashCode();
    result = 31 * result + proxy.hashCode();
    result = 31 * result + inetSocketAddress.hashCode();
    return result;
  }
}
