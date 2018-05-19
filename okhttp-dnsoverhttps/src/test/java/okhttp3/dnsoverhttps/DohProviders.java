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
import java.util.List;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

import static okhttp3.dnsoverhttps.DnsOverHttps.DNS_MESSAGE;
import static okhttp3.dnsoverhttps.DnsOverHttps.UDPWIREFORMAT;

/**
 * Temporary registry of known DNS over HTTPS providers.
 *
 * https://github.com/curl/curl/wiki/DNS-over-HTTPS
 */
public class DohProviders {
  static DnsOverHttps buildGoogle(OkHttpClient bootstrapClient) {
    HttpUrl url = parseUrl("https://dns.google.com/experimental?ct=" + UDPWIREFORMAT);

    BootstrapDns bootstrapDns = new BootstrapDns("dns.google.com", getByIp("216.58.204.78"),
        getByIp("2a00:1450:4009:814:0:0:0:200e"));

    return new DnsOverHttps(bootstrapClient, url, bootstrapDns, true, "GET",
        UDPWIREFORMAT);
  }

  static DnsOverHttps buildGooglePost(OkHttpClient bootstrapClient) {
    HttpUrl url = parseUrl("https://dns.google.com/experimental");

    BootstrapDns bootstrapDns = new BootstrapDns("dns.google.com", getByIp("216.58.204.78"),
        getByIp("2a00:1450:4009:814:0:0:0:200e"));

    return new DnsOverHttps(bootstrapClient, url, bootstrapDns, true, "POST",
        UDPWIREFORMAT);
  }

  static DnsOverHttps buildCloudflare(OkHttpClient bootstrapClient) {
    HttpUrl url = parseUrl("https://cloudflare-dns.com/dns-query?ct=application/dns-udpwireformat");

    BootstrapDns bootstrapDns = new BootstrapDns("cloudflare-dns.com", getByIp("104.16.111.25"),
        getByIp("104.16.112.25"),
        getByIp("2400:cb00:2048:1:0:0:6810:7019"),
        getByIp("2400:cb00:2048:1:0:0:6810:6f19"));

    return new DnsOverHttps(bootstrapClient, url, bootstrapDns, false, "GET", DNS_MESSAGE);
  }

  static DnsOverHttps buildCloudflarePost(OkHttpClient bootstrapClient) {
    HttpUrl url = parseUrl("https://dns.cloudflare.com/.well-known/dns-query");

    return new DnsOverHttps(bootstrapClient, url, null, false, "POST",
        UDPWIREFORMAT);
  }

  static DnsOverHttps buildCleanBrowsing(OkHttpClient bootstrapClient) {
    return new DnsOverHttps(bootstrapClient,
        parseUrl("https://doh.cleanbrowsing.org/doh/family-filter"), null, false, "GET",
        DNS_MESSAGE);
  }

  static DnsOverHttps buildChantra(OkHttpClient bootstrapClient) {
    return new DnsOverHttps(bootstrapClient, parseUrl("https://dns.dnsoverhttps.net/dns-query"),
        null, false, "GET", DNS_MESSAGE);
  }

  static DnsOverHttps buildCryptoSx(OkHttpClient bootstrapClient) {
    return new DnsOverHttps(bootstrapClient, parseUrl("https://doh.crypto.sx/dns-query"), null,
        false, "GET", DNS_MESSAGE);
  }

  static DnsOverHttps buildSecureDns(OkHttpClient bootstrapClient) {
    return new DnsOverHttps(bootstrapClient, parseUrl("https://doh.securedns.eu/dns-query"), null,
        false, "GET", DNS_MESSAGE);
  }

  public static List<DnsOverHttps> providers(OkHttpClient client, boolean http2Only,
      boolean workingOnly, boolean getOnly) {

    List<DnsOverHttps> result = new ArrayList<>();

    result.add(buildGoogle(client));
    if (!getOnly) {
      result.add(buildGooglePost(client));
    }
    result.add(buildCloudflare(client));
    if (!getOnly) {
      result.add(buildCloudflarePost(client));
    }
    result.add(buildCleanBrowsing(client));
    if (!http2Only) {
      result.add(buildSecureDns(client));
    }
    if (!workingOnly) {
      result.add(buildCryptoSx(client)); // 521 - server down
      result.add(buildChantra(client)); // 400
    }

    return result;
  }

  static HttpUrl parseUrl(String s) {
    HttpUrl url = HttpUrl.parse(s);

    if (url == null) {
      throw new NullPointerException("unable to parse url");
    }

    return url;
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
