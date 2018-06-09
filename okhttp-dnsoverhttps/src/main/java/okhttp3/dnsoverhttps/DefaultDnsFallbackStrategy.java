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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import okhttp3.internal.publicsuffix.PublicSuffixDatabase;

public class DefaultDnsFallbackStrategy implements DnsFallbackStrategy {
  private final @Nullable String dnsOverHttpHost;
  private final List<DnsSource> publicResponse;
  private final List<DnsSource> privateResponse;
  private final List<DnsSource> dnsHostResponse;

  public DefaultDnsFallbackStrategy() {
    this(false, null);
  }

  public DefaultDnsFallbackStrategy(boolean fallback, String dnsOverHttpHost,
      InetAddress... dnsOverHttpAddresses) {
    this.dnsOverHttpHost = dnsOverHttpHost;

    if (fallback) {
      this.publicResponse = immutableList(DnsOverHttps.instance, SystemDns.instance);
    } else {
      this.publicResponse = immutableList((DnsSource) DnsOverHttps.instance);
    }
    this.privateResponse = immutableList((DnsSource) SystemDns.instance);
    if (dnsOverHttpAddresses.length > 0) {
      this.dnsHostResponse =
          immutableList((DnsSource) new HardcodedResponse(immutableList(dnsOverHttpAddresses)));
    } else {
      this.dnsHostResponse = immutableList((DnsSource) SystemDns.instance);
    }
  }

  private <T> List<T> immutableList(T... addresses) {
    return Collections.unmodifiableList(Arrays.asList(addresses));
  }

  @Override public List<DnsSource> sources(String host) {
    if (host.equals(dnsOverHttpHost)) {
      return dnsHostResponse;
    }

    if (isPrivateHost(host)) {
      return privateResponse;
    } else {
      return publicResponse;
    }
  }

  private boolean isPrivateHost(String host) {
    return PublicSuffixDatabase.get().getEffectiveTldPlusOne(host) == null;
  }
}
