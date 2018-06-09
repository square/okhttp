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
import java.util.List;

public interface DnsFallbackStrategy {
  interface DnsSource {
  }

  class DnsOverHttps implements DnsSource {
    public static final DnsOverHttps instance = new DnsOverHttps();

    private DnsOverHttps() {
    }

    @Override public int hashCode() {
      return 1;
    }

    @Override public boolean equals(Object obj) {
      return obj instanceof DnsOverHttps;
    }

    @Override public String toString() {
      return "DnsOverHttps";
    }
  }

  class SystemDns implements DnsSource {
    public static final SystemDns instance = new SystemDns();

    private SystemDns() {
    }

    @Override public int hashCode() {
      return 2;
    }

    @Override public boolean equals(Object obj) {
      return obj instanceof SystemDns;
    }

    @Override public String toString() {
      return "SystemDns";
    }
  }

  class HardcodedResponse implements DnsSource {
    public final List<InetAddress> dnsHostResponse;

    public HardcodedResponse(List<InetAddress> dnsHostResponse) {
      this.dnsHostResponse = dnsHostResponse;
    }

    @Override public int hashCode() {
      return dnsHostResponse.hashCode();
    }

    @Override public boolean equals(Object obj) {
      if (!(obj instanceof HardcodedResponse)) {
        return false;
      }

      return ((HardcodedResponse) obj).dnsHostResponse.equals(dnsHostResponse);
    }

    @Override public String toString() {
      return "HardcodedResponse{" + dnsHostResponse + "}";
    }
  }

  List<DnsSource> sources(String host);
}
