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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static okhttp3.dnsoverhttps.DnsOverHttps.isPrivateHost;

/**
 * A strategy for handling DNS lookup failures for DNS over HTTPS.
 */
public interface DnsFallbackStrategy {
  /**
   * Default fallback strategy that uses System DNS only for private network addresses,
   * and otherwise tries DNS over HTTPS for public addresses, but fallback to System DNS.
   */
  DnsFallbackStrategy DEFAULT = new DnsFallbackStrategy() {
    @Override public List<DnsSource> sources(String host) {
      if (isPrivateHost(host)) {
        return Collections.singletonList(DnsSource.SYSTEM_DNS);
      } else {
        return Arrays.asList(DnsSource.DNS_OVER_HTTP, DnsSource.SYSTEM_DNS);
      }
    }
  };

  /**
   * Simple DNS over HTTPS only strategy.  Likely to have issues for internal network addresses
   * unless the DNS over HTTPS server is hosted locally.
   */
  DnsFallbackStrategy NO_FALLBACK = new DnsFallbackStrategy() {
    @Override public List<DnsSource> sources(String host) {
      return Collections.singletonList(DnsSource.DNS_OVER_HTTP);
    }
  };

  enum DnsSource {
    DNS_OVER_HTTP, SYSTEM_DNS;
  }

  List<DnsSource> sources(String host);
}
