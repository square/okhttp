/*
 * Copyright (C) 2018 Square, Inc.
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
import java.util.List;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

/**
 * Temporary registry of known DNS over HTTPS providers.
 *
 * https://github.com/curl/curl/wiki/DNS-over-HTTPS
 */
public class DohProviders {
  static DnsOverHttps buildGoogle(OkHttpClient bootstrapClient) {
    return new DnsOverHttps.Builder().client(bootstrapClient)
        .url(HttpUrl.get("https://dns.google/dns-query"))
        .bootstrapDnsHosts(getByIp("8.8.4.4"), getByIp("8.8.8.8"))
        .build();
  }

  static DnsOverHttps buildGooglePost(OkHttpClient bootstrapClient) {
    return new DnsOverHttps.Builder().client(bootstrapClient)
        .url(HttpUrl.get("https://dns.google/dns-query"))
        .bootstrapDnsHosts(getByIp("8.8.4.4"), getByIp("8.8.8.8"))
        .post(true)
        .build();
  }

  static DnsOverHttps buildCloudflareIp(OkHttpClient bootstrapClient) {
    return new DnsOverHttps.Builder().client(bootstrapClient)
        .url(HttpUrl.get("https://1.1.1.1/dns-query"))
        .includeIPv6(false)
        .build();
  }

  static DnsOverHttps buildCloudflare(OkHttpClient bootstrapClient) {
    return new DnsOverHttps.Builder().client(bootstrapClient)
        .url(HttpUrl.get("https://1.1.1.1/dns-query"))
        .bootstrapDnsHosts(getByIp("1.1.1.1"), getByIp("1.0.0.1"))
        .includeIPv6(false)
        .build();
  }

  static DnsOverHttps buildCloudflarePost(OkHttpClient bootstrapClient) {
    return new DnsOverHttps.Builder().client(bootstrapClient)
        .url(HttpUrl.get("https://cloudflare-dns.com/dns-query"))
        .bootstrapDnsHosts(getByIp("1.1.1.1"), getByIp("1.0.0.1"))
        .includeIPv6(false)
        .post(true)
        .build();
  }

  static DnsOverHttps buildCleanBrowsing(OkHttpClient bootstrapClient) {
    return new DnsOverHttps.Builder().client(bootstrapClient)
        .url(HttpUrl.get("https://doh.cleanbrowsing.org/doh/family-filter/"))
        .includeIPv6(false)
        .build();
  }

  static DnsOverHttps buildChantra(OkHttpClient bootstrapClient) {
    return new DnsOverHttps.Builder().client(bootstrapClient)
        .url(HttpUrl.get("https://dns.dnsoverhttps.net/dns-query"))
        .includeIPv6(false)
        .build();
  }

  static DnsOverHttps buildCryptoSx(OkHttpClient bootstrapClient) {
    return new DnsOverHttps.Builder().client(bootstrapClient)
        .url(HttpUrl.get("https://doh.crypto.sx/dns-query"))
        .includeIPv6(false)
        .build();
  }

  public static List<DnsOverHttps> providers(OkHttpClient client, boolean http2Only,
      boolean workingOnly, boolean getOnly) {

    List<DnsOverHttps> result = new ArrayList<>();

    result.add(buildGoogle(client));
    if (!getOnly) {
      result.add(buildGooglePost(client));
    }
    result.add(buildCloudflare(client));
    result.add(buildCloudflareIp(client));
    if (!getOnly) {
      result.add(buildCloudflarePost(client));
    }
    if (!workingOnly) {
      //result.add(buildCleanBrowsing(client)); // timeouts
      result.add(buildCryptoSx(client)); // 521 - server down
    }
    result.add(buildChantra(client));

    return result;
  }

  private static InetAddress getByIp(String host) {
    try {
      return InetAddress.getByName(host);
    } catch (UnknownHostException e) {
      // unlikely
      throw new RuntimeException(e);
    }
  }
}
