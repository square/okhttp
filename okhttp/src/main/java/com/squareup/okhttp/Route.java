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

import java.net.InetSocketAddress;
import java.net.Proxy;

/** Represents the route used by a connection to reach an endpoint. */
public class Route {
  final Address address;
  final Proxy proxy;
  final InetSocketAddress inetSocketAddress;
  final boolean modernTls;

  public Route(Address address, Proxy proxy, InetSocketAddress inetSocketAddress,
      boolean modernTls) {
    if (address == null) throw new NullPointerException("address == null");
    if (proxy == null) throw new NullPointerException("proxy == null");
    if (inetSocketAddress == null) throw new NullPointerException("inetSocketAddress == null");
    this.address = address;
    this.proxy = proxy;
    this.inetSocketAddress = inetSocketAddress;
    this.modernTls = modernTls;
  }

  /** Returns the {@link Address} of this route. */
  public Address getAddress() {
    return address;
  }

  /**
   * Returns the {@link Proxy} of this route.
   *
   * <strong>Warning:</strong> This may be different than the proxy returned
   * by {@link #getAddress}! That is the proxy that the user asked to be
   * connected to; this returns the proxy that they were actually connected
   * to. The two may disagree when a proxy selector selects a different proxy
   * for a connection.
   */
  public Proxy getProxy() {
    return proxy;
  }

  /** Returns the {@link InetSocketAddress} of this route. */
  public InetSocketAddress getSocketAddress() {
    return inetSocketAddress;
  }

  /** Returns true if this route uses modern TLS. */
  public boolean isModernTls() {
    return modernTls;
  }

  /** Returns a copy of this route with flipped TLS mode. */
  Route flipTlsMode() {
    return new Route(address, proxy, inetSocketAddress, !modernTls);
  }

  @Override public boolean equals(Object obj) {
    if (obj instanceof Route) {
      Route other = (Route) obj;
      return (address.equals(other.address)
          && proxy.equals(other.proxy)
          && inetSocketAddress.equals(other.inetSocketAddress)
          && modernTls == other.modernTls);
    }
    return false;
  }

  @Override public int hashCode() {
    int result = 17;
    result = 31 * result + address.hashCode();
    result = 31 * result + proxy.hashCode();
    result = 31 * result + inetSocketAddress.hashCode();
    result = result + (modernTls ? (31 * result) : 0);
    return result;
  }
}
