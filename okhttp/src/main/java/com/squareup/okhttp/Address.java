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
import java.util.List;

import static com.squareup.okhttp.internal.Util.equal;

/**
 * A specification for a connection to an origin server. For simple connections,
 * this is the server's hostname and port. If an explicit proxy is requested (or
 * {@linkplain Proxy#NO_PROXY no proxy} is explicitly requested), this also includes
 * that proxy information. For secure connections the address also includes the
 * SSL socket factory and hostname verifier.
 *
 * <p>HTTP requests that share the same {@code Address} may also share the same
 * {@link Connection}.
 */
public final class Address {
  final Proxy proxy;
  final String uriHost;
  final int uriPort;
  final List<ConnectionConfiguration> connectionConfigurations;

  public Address(String uriHost, int uriPort, Proxy proxy,
      List<ConnectionConfiguration> connectionConfigurations) {
    if (uriHost == null) {
      throw new NullPointerException("uriHost == null");
    }
    if (uriPort <= 0) {
      throw new IllegalArgumentException("uriPort <= 0: " + uriPort);
    }
    if (connectionConfigurations.isEmpty()) {
      throw new IllegalArgumentException("connectionConfigurations.isEmpty()");
    }
    this.proxy = proxy;
    this.uriHost = uriHost;
    this.uriPort = uriPort;
    this.connectionConfigurations = Util.immutableList(connectionConfigurations);
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

  public List<ConnectionConfiguration> getConnectionConfigurations() {
    return connectionConfigurations;
  }

  /**
   * Returns this address's explicitly-specified HTTP proxy, or null to
   * delegate to the HTTP client's proxy selector.
   */
  public Proxy getProxy() {
    return proxy;
  }

  @Override public boolean equals(Object other) {
    if (other instanceof Address) {
      Address that = (Address) other;
      return equal(this.proxy, that.proxy)
          && this.uriHost.equals(that.uriHost)
          && this.uriPort == that.uriPort
          && this.connectionConfigurations.equals(that.connectionConfigurations);
    }
    return false;
  }

  @Override public int hashCode() {
    int result = 17;
    result = 31 * result + uriHost.hashCode();
    result = 31 * result + uriPort;
    result = 31 * result + (proxy != null ? proxy.hashCode() : 0);
    result = 31 * result + connectionConfigurations.hashCode();
    return result;
  }
}
