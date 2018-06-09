/*
 * Copyright (C) 2014 Square, Inc.
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

package okhttp3.dnsoverhttps;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import okhttp3.Dns;

/**
 * Internal Bootstrap DNS implementation for handling initial connection to DNS over HTTPS server.
 *
 * Returns hardcoded results for the known host.
 */
final class BootstrapDns implements Dns {
  private final String dnsHost;
  private final @Nullable List<InetAddress> dnsServers;
  private final @Nullable Dns systemDns;

  public BootstrapDns(@Nullable Dns systemDns, String dnsHost, @Nullable List<InetAddress> dnsServers) {
    this.systemDns = systemDns;
    this.dnsHost = dnsHost;
    if (dnsServers != null && !dnsServers.isEmpty()) {
      this.dnsServers = dnsServers;
    } else {
      this.dnsServers = null;
    }

    if (this.systemDns == null && this.dnsServers == null) {
      throw new IllegalStateException("No configured system DNS source");
    }
  }

  @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
    if (!hostname.equals(dnsHost)) {
      throw new UnknownHostException("BootstrapDns used for external lookup: " + hostname);
    }

    if (systemDns == null) {
      return dnsServers;
    }

    if (dnsServers == null) {
      return systemDns.lookup(hostname);
    }

    List<InetAddress> addresses = new ArrayList<>();

    addresses.addAll(dnsServers);
    try {
      addresses.addAll(systemDns.lookup(hostname));
    } catch (UnknownHostException uhe) {
      // ignored as we default to hardcoded addresses
    }

    return addresses;
  }
}
