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
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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
  override fun newCall(request: Dns.Request): Dns.Call {
    val calls = callsList(request.hostname)

    val canceledException = validate(request.hostname)
    if (canceledException != null) {
      for (call in calls) {
        call.cancel()
      }
    }

    return DnsOverHttpsCall(
      request = request,
      calls = calls,
      canceledException = canceledException,
    )
  }

  /**
   * Returns an exception if [hostname] should not be resolved.
   *
   * We **return** this exception rather than throwing it because in the [Dns.Callback] case we want
   * `onFailure()` to be called on a dispatcher thread and not synchronously.
   */
  private fun validate(hostname: String): UnknownHostException? {
    // Don't load the public suffix list unless necessary.
    if (resolvePrivateAddresses && resolvePublicAddresses) return null

    val privateHost = isPrivateHost(hostname)

    return when {
      privateHost && !resolvePrivateAddresses -> UnknownHostException("private hosts not resolved")
      !privateHost && !resolvePublicAddresses -> UnknownHostException("public hosts not resolved")
      else -> null
    }
  }

  @Throws(UnknownHostException::class)
  override fun lookup(hostname: String): List<InetAddress> {
    val validationException = validate(hostname)
    if (validationException != null) throw validationException

    val calls = callsList(hostname, inetAddressesOnly = true)

    val failures = ArrayList<Exception>(3)
    val results = ArrayList<InetAddress>(5)
    executeRequests(calls, results, failures)

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
      val addresses =
        decodeResponse(response)
          .filterIsInstance<ResourceRecord.IpAddress>()
          .map { it.address }
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

  internal fun createCall(
    hostname: String,
    type: Int,
  ): Call =
    client.newCall(
      request =
        Request
          .Builder()
          .header("Accept", DNS_MESSAGE.toString())
          .apply {
            val dnsUrl = this@DnsOverHttps.url
            if (post) {
              url(dnsUrl)
              cacheUrlOverride(
                dnsUrl
                  .newBuilder()
                  .addQueryParameter("hostname", hostname)
                  .build(),
              )
              post(QueryRequestBody(DnsMessage.query(hostname, type)))
            } else {
              val queryParameter = DnsMessage.query(hostname, type).asQueryParameter()
              val requestUrl =
                dnsUrl
                  .newBuilder()
                  .addQueryParameter("dns", queryParameter)
                  .build()
              url(requestUrl)
            }
          }.build(),
    )

  private fun callsList(
    hostname: String,
    inetAddressesOnly: Boolean = false,
  ): List<Call> =
    buildList {
      if (includeHttps && !inetAddressesOnly) {
        add(createCall(hostname, TYPE_HTTPS))
      }

      if (includeIPv6) {
        add(createCall(hostname, TYPE_AAAA))
      }

      add(createCall(hostname, TYPE_A))
    }

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
    fun includeHttps(includeHttps: Boolean) =
      apply {
        this.includeHttps = includeHttps
      }

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
