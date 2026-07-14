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

import java.io.IOException
import java.net.InetAddress
import java.net.ProtocolException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.platform.Platform
import okhttp3.internal.publicsuffix.PublicSuffixDatabase

/**
 * [DNS over HTTPS implementation][doh_spec].
 *
 * > A DNS API client encodes a single DNS query into an HTTP request
 * > using either the HTTP GET or POST method and the other requirements
 * > of this section.  The DNS API server defines the URI used by the
 * > request through the use of a URI Template.
 *
 * [doh_spec]: https://tools.ietf.org/html/draft-ietf-doh-dns-over-https-13
 */
class DnsOverHttps internal constructor(
  @get:JvmName("client") val client: OkHttpClient,
  @get:JvmName("url") val url: HttpUrl,
  @get:JvmName("includeIPv6") val includeIPv6: Boolean,
  @get:JvmName("includeHttps") val includeHttps: Boolean,
  @get:JvmName("post") val post: Boolean,
  @get:JvmName("resolvePrivateAddresses") val resolvePrivateAddresses: Boolean,
  @get:JvmName("resolvePublicAddresses") val resolvePublicAddresses: Boolean,
) : Dns {
  @Throws(UnknownHostException::class)
  override fun lookup(hostname: String): List<InetAddress> {
    if (!resolvePrivateAddresses || !resolvePublicAddresses) {
      val privateHost = isPrivateHost(hostname)

      if (privateHost && !resolvePrivateAddresses) {
        throw UnknownHostException("private hosts not resolved")
      }

      if (!privateHost && !resolvePublicAddresses) {
        throw UnknownHostException("public hosts not resolved")
      }
    }

    return lookupHttps(hostname)
  }

  @Throws(UnknownHostException::class)
  private fun lookupHttps(hostname: String): List<InetAddress> {
    val networkRequests =
      buildList {
        add(client.newCall(buildRequest(hostname, TYPE_A)))

        if (includeHttps) {
          add(client.newCall(buildRequest(hostname, TYPE_HTTPS)))
        }

        if (includeIPv6) {
          add(client.newCall(buildRequest(hostname, TYPE_AAAA)))
        }
      }

    val failures = ArrayList<Exception>(3)
    val results = ArrayList<InetAddress>(5)
    executeRequests(networkRequests, results, failures)

    return results.ifEmpty {
      throwBestFailure(hostname, failures)
    }
  }

  private fun executeRequests(
    networkRequests: List<Call>,
    responses: MutableList<InetAddress>,
    failures: MutableList<Exception>,
  ) {
    val latch = CountDownLatch(networkRequests.size)

    for (call in networkRequests) {
      call.enqueue(
        object : Callback {
          override fun onFailure(
            call: Call,
            e: IOException,
          ) {
            synchronized(failures) {
              failures.add(e)
            }
            latch.countDown()
          }

          override fun onResponse(
            call: Call,
            response: Response,
          ) {
            processResponse(response, responses, failures)
            latch.countDown()
          }
        },
      )
    }

    try {
      latch.await()
    } catch (e: InterruptedException) {
      failures.add(e)
    }
  }

  private fun processResponse(
    response: Response,
    results: MutableList<InetAddress>,
    failures: MutableList<Exception>,
  ) {
    try {
      val addresses = readResponse(response)
      synchronized(results) {
        results.addAll(addresses)
      }
    } catch (e: IOException) {
      synchronized(failures) {
        failures.add(e)
      }
    }
  }

  @Throws(UnknownHostException::class)
  private fun throwBestFailure(
    hostname: String,
    failures: List<Exception>,
  ): List<InetAddress> {
    if (failures.isEmpty()) {
      throw UnknownHostException(hostname)
    }

    val failure = failures[0]

    if (failure is UnknownHostException) {
      throw failure
    }

    val unknownHostException = UnknownHostException(hostname)
    unknownHostException.initCause(failure)

    for (i in 1 until failures.size) {
      unknownHostException.addSuppressed(failures[i])
    }

    throw unknownHostException
  }

  @Throws(IOException::class)
  private fun readResponse(response: Response): List<InetAddress> {
    if (
      response.cacheResponse == null &&
      response.protocol !== Protocol.HTTP_2 &&
      response.protocol !== Protocol.QUIC
    ) {
      Platform.get().log("Incorrect protocol: ${response.protocol}", Platform.WARN)
    }

    response.use {
      if (!response.isSuccessful) {
        throw IOException("response: ${response.code} ${response.message}")
      }

      val body = response.body
      if (body.contentLength() > MAX_RESPONSE_SIZE) {
        throw ProtocolException(
          "response size exceeds limit ($MAX_RESPONSE_SIZE bytes): ${body.contentLength()} bytes",
        )
      }

      val dnsResponse = DnsMessageReader(body.source()).read()
      when (dnsResponse.responseCode) {
        RESPONSE_CODE_SUCCESS -> {
          return dnsResponse.answers
            .filterIsInstance<ResourceRecord.IpAddress>()
            .map { it.address }
        }

        RESPONSE_CODE_SERVER_FAILURE -> {
          throw UnknownHostException("DNS server failure")
        }

        else -> {
          throw UnknownHostException()
        }
      }
    }
  }

  private fun buildRequest(
    hostname: String,
    type: Int,
  ): Request =
    Request
      .Builder()
      .header("Accept", DNS_MESSAGE.toString())
      .apply {
        val dnsUrl: HttpUrl = this@DnsOverHttps.url
        if (post) {
          url(dnsUrl)
            .cacheUrlOverride(
              dnsUrl
                .newBuilder()
                .addQueryParameter("hostname", hostname)
                .build(),
            ).post(QueryRequestBody(DnsMessage.query(hostname, type)))
        } else {
          val queryParameter = DnsMessage.query(hostname, type).asQueryParameter()
          val requestUrl = dnsUrl.newBuilder().addQueryParameter("dns", queryParameter).build()
          url(requestUrl)
        }
      }.build()

  class Builder {
    internal var client: OkHttpClient? = null
    internal var url: HttpUrl? = null
    internal var includeIPv6 = true
    internal var includeHttps = false
    internal var post = false
    internal var systemDns = Dns.SYSTEM
    internal var bootstrapDnsHosts: List<InetAddress>? = null
    internal var resolvePrivateAddresses = false
    internal var resolvePublicAddresses = true

    fun build(): DnsOverHttps {
      val client = this.client ?: throw NullPointerException("client not set")
      return DnsOverHttps(
        client.newBuilder().dns(buildBootstrapClient(this)).build(),
        checkNotNull(url) { "url not set" },
        includeIPv6,
        includeHttps,
        post,
        resolvePrivateAddresses,
        resolvePublicAddresses,
      )
    }

    fun client(client: OkHttpClient) =
      apply {
        this.client = client
      }

    fun url(url: HttpUrl) =
      apply {
        this.url = url
      }

    /**
     * True to request [`HTTPS` DNS records](https://datatracker.ietf.org/doc/rfc9460/), which are
     * necessary for [Encrypted Client Hello (ECH)](https://datatracker.ietf.org/doc/rfc9849/).
     *
     * This is false by default, but that default is subject to change in 2026.
     */
    fun includeIPv6(includeIPv6: Boolean) =
      apply {
        this.includeIPv6 = includeIPv6
      }

    fun post(post: Boolean) =
      apply {
        this.post = post
      }

    fun resolvePrivateAddresses(resolvePrivateAddresses: Boolean) =
      apply {
        this.resolvePrivateAddresses = resolvePrivateAddresses
      }

    fun resolvePublicAddresses(resolvePublicAddresses: Boolean) =
      apply {
        this.resolvePublicAddresses = resolvePublicAddresses
      }

    fun bootstrapDnsHosts(bootstrapDnsHosts: List<InetAddress>?) =
      apply {
        this.bootstrapDnsHosts = bootstrapDnsHosts
      }

    fun bootstrapDnsHosts(vararg bootstrapDnsHosts: InetAddress): Builder = bootstrapDnsHosts(bootstrapDnsHosts.toList())

    fun systemDns(systemDns: Dns) =
      apply {
        this.systemDns = systemDns
      }
  }

  companion object {
    val DNS_MESSAGE: MediaType = "application/dns-message".toMediaType()
    const val MAX_RESPONSE_SIZE = 64 * 1024

    private fun buildBootstrapClient(builder: Builder): Dns {
      val hosts = builder.bootstrapDnsHosts

      return if (hosts != null) {
        BootstrapDns(builder.url!!.host, hosts)
      } else {
        builder.systemDns
      }
    }

    internal fun isPrivateHost(host: String): Boolean = PublicSuffixDatabase.get().getEffectiveTldPlusOne(host) == null
  }
}
