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
package okhttp3.dnsoverhttps

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * Temporary registry of known DNS over HTTPS providers.
 *
 * https://github.com/curl/curl/wiki/DNS-over-HTTPS
 */
object DohProviders {
  private fun buildGoogle(bootstrapClient: OkHttpClient): DnsOverHttps {
    return DnsOverHttps.Builder()
      .client(bootstrapClient)
      .url("https://dns.google/dns-query".toHttpUrl())
      .bootstrapDnsHosts(getByIp("8.8.4.4"), getByIp("8.8.8.8"))
      .build()
  }

  private fun buildGooglePost(bootstrapClient: OkHttpClient): DnsOverHttps {
    return DnsOverHttps.Builder()
      .client(bootstrapClient)
      .url("https://dns.google/dns-query".toHttpUrl())
      .bootstrapDnsHosts(getByIp("8.8.4.4"), getByIp("8.8.8.8"))
      .post(true)
      .build()
  }

  private fun buildCloudflareIp(bootstrapClient: OkHttpClient): DnsOverHttps {
    return DnsOverHttps.Builder()
      .client(bootstrapClient)
      .url("https://1.1.1.1/dns-query".toHttpUrl())
      .includeIPv6(false)
      .build()
  }

  private fun buildCloudflare(bootstrapClient: OkHttpClient): DnsOverHttps {
    return DnsOverHttps.Builder()
      .client(bootstrapClient)
      .url("https://1.1.1.1/dns-query".toHttpUrl())
      .bootstrapDnsHosts(getByIp("1.1.1.1"), getByIp("1.0.0.1"))
      .includeIPv6(false)
      .build()
  }

  private fun buildCloudflarePost(bootstrapClient: OkHttpClient): DnsOverHttps {
    return DnsOverHttps.Builder()
      .client(bootstrapClient)
      .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
      .bootstrapDnsHosts(getByIp("1.1.1.1"), getByIp("1.0.0.1"))
      .includeIPv6(false)
      .post(true)
      .build()
  }

  fun buildCleanBrowsing(bootstrapClient: OkHttpClient): DnsOverHttps {
    return DnsOverHttps.Builder()
      .client(bootstrapClient)
      .url("https://doh.cleanbrowsing.org/doh/family-filter/".toHttpUrl())
      .includeIPv6(false)
      .build()
  }

  private fun buildChantra(bootstrapClient: OkHttpClient): DnsOverHttps {
    return DnsOverHttps.Builder()
      .client(bootstrapClient)
      .url("https://dns.dnsoverhttps.net/dns-query".toHttpUrl())
      .includeIPv6(false)
      .build()
  }

  private fun buildCryptoSx(bootstrapClient: OkHttpClient): DnsOverHttps {
    return DnsOverHttps.Builder()
      .client(bootstrapClient)
      .url("https://doh.crypto.sx/dns-query".toHttpUrl())
      .includeIPv6(false)
      .build()
  }

  @JvmStatic
  fun providers(
    client: OkHttpClient,
    http2Only: Boolean,
    workingOnly: Boolean,
    getOnly: Boolean,
  ): List<DnsOverHttps> {
    return buildList {
      add(buildGoogle(client))
      if (!getOnly) {
        add(buildGooglePost(client))
      }
      add(buildCloudflare(client))
      add(buildCloudflareIp(client))
      if (!getOnly) {
        add(buildCloudflarePost(client))
      }
      if (!workingOnly) {
        // result += buildCleanBrowsing(client); // timeouts
        add(buildCryptoSx(client)) // 521 - server down
      }
      add(buildChantra(client))
    }
  }

  private fun getByIp(host: String): InetAddress {
    return try {
      InetAddress.getByName(host)
    } catch (e: UnknownHostException) {
      // unlikely
      throw RuntimeException(e)
    }
  }
}
