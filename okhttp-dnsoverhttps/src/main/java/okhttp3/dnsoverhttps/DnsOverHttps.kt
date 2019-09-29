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

import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.internal.platform.Platform
import okhttp3.internal.publicsuffix.PublicSuffixDatabase
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.ArrayList
import java.util.concurrent.CountDownLatch

/**
 * [DNS over HTTPS implementation][doh_spec].
 *
 * > A DNS API client encodes a single DNS query into an HTTP request
 * > using either the HTTP GET or POST method and the other requirements
 * > of this section.  The DNS API server defines the URI used by the
 * > request through the use of a URI Template.
 *
 * ### Warning: This is a non-final API.
 *
 * As of OkHttp 3.14, this feature is an unstable preview: the API is subject to change, and the
 * implementation is incomplete. We expect that OkHttp 4.0 or 4.1 will finalize this API. Until
 * then, expect API and behavior changes when you update your OkHttp dependency.**
 *
 * [doh_spec]: https://tools.ietf.org/html/draft-ietf-doh-dns-over-https-13
 */
class DnsOverHttps internal constructor(
  @get:JvmName("client") val client: OkHttpClient,
  @get:JvmName("url") val url: HttpUrl,
  @get:JvmName("includeIPv6") val includeIPv6: Boolean,
  @get:JvmName("post") val post: Boolean,
  @get:JvmName("resolvePrivateAddresses") val resolvePrivateAddresses: Boolean,
  @get:JvmName("resolvePublicAddresses") val resolvePublicAddresses: Boolean
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
    val networkRequests = ArrayList<Call>(2)
    val failures = ArrayList<Exception>(2)
    val results = ArrayList<InetAddress>(5)

    buildRequest(hostname, networkRequests, results, failures, DnsRecordCodec.TYPE_A)

    if (includeIPv6) {
      buildRequest(hostname, networkRequests, results, failures, DnsRecordCodec.TYPE_AAAA)
    }

    executeRequests(hostname, networkRequests, results, failures)

    return if (results.isNotEmpty()) {
      results
    } else {
      throwBestFailure(hostname, failures)
    }
  }

  private fun buildRequest(
    hostname: String,
    networkRequests: MutableList<Call>,
    results: MutableList<InetAddress>,
    failures: MutableList<Exception>,
    type: Int
  ) {
    val request = buildRequest(hostname, type)
    val response = getCacheOnlyResponse(request)

    response?.let { processResponse(it, hostname, results, failures) } ?: networkRequests.add(
        client.newCall(request))
  }

  private fun executeRequests(
    hostname: String,
    networkRequests: List<Call>,
    responses: MutableList<InetAddress>,
    failures: MutableList<Exception>
  ) {
    val latch = CountDownLatch(networkRequests.size)

    for (call in networkRequests) {
      call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
          synchronized(failures) {
            failures.add(e)
          }
          latch.countDown()
        }

        override fun onResponse(call: Call, response: Response) {
          processResponse(response, hostname, responses, failures)
          latch.countDown()
        }
      })
    }

    try {
      latch.await()
    } catch (e: InterruptedException) {
      failures.add(e)
    }
  }

  private fun processResponse(
    response: Response,
    hostname: String,
    results: MutableList<InetAddress>,
    failures: MutableList<Exception>
  ) {
    try {
      val addresses = readResponse(hostname, response)
      synchronized(results) {
        results.addAll(addresses)
      }
    } catch (e: Exception) {
      synchronized(failures) {
        failures.add(e)
      }
    }
  }

  @Throws(UnknownHostException::class)
  private fun throwBestFailure(hostname: String, failures: List<Exception>): List<InetAddress> {
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

  private fun getCacheOnlyResponse(request: Request): Response? {
    if (!post && client.cache != null) {
      try {
        val cacheRequest = request.newBuilder().cacheControl(CacheControl.FORCE_CACHE).build()

        val cacheResponse = client.newCall(cacheRequest).execute()

        if (cacheResponse.code != 504) {
          return cacheResponse
        }
      } catch (ioe: IOException) {
        // Failures are ignored as we can fallback to the network
        // and hopefully repopulate the cache.
      }
    }

    return null
  }

  @Throws(Exception::class)
  private fun readResponse(hostname: String, response: Response): List<InetAddress> {
    if (response.cacheResponse == null && response.protocol !== Protocol.HTTP_2) {
      Platform.get().log("Incorrect protocol: ${response.protocol}", Platform.WARN)
    }

    response.use {
      if (!response.isSuccessful) {
        throw IOException("response: " + response.code + " " + response.message)
      }

      val body = response.body

      if (body!!.contentLength() > MAX_RESPONSE_SIZE) {
        throw IOException(
            "response size exceeds limit ($MAX_RESPONSE_SIZE bytes): ${body.contentLength()} bytes"
        )
      }

      val responseBytes = body.source().readByteString()

      return DnsRecordCodec.decodeAnswers(hostname, responseBytes)
    }
  }

  private fun buildRequest(hostname: String, type: Int): Request =
      Request.Builder().header("Accept", DNS_MESSAGE.toString()).apply {
        val query = DnsRecordCodec.encodeQuery(hostname, type)

        if (post) {
          url(url).post(query.toRequestBody(DNS_MESSAGE))
        } else {
          val encoded = query.base64Url().replace("=", "")
          val requestUrl = url.newBuilder().addQueryParameter("dns", encoded).build()

          url(requestUrl)
        }
      }.build()

  class Builder {
    internal var client: OkHttpClient? = null
    internal var url: HttpUrl? = null
    internal var includeIPv6 = true
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
          post,
          resolvePrivateAddresses,
          resolvePublicAddresses
      )
    }

    fun client(client: OkHttpClient) = apply {
      this.client = client
    }

    fun url(url: HttpUrl) = apply {
      this.url = url
    }

    fun includeIPv6(includeIPv6: Boolean) = apply {
      this.includeIPv6 = includeIPv6
    }

    fun post(post: Boolean) = apply {
      this.post = post
    }

    fun resolvePrivateAddresses(resolvePrivateAddresses: Boolean) = apply {
      this.resolvePrivateAddresses = resolvePrivateAddresses
    }

    fun resolvePublicAddresses(resolvePublicAddresses: Boolean) = apply {
      this.resolvePublicAddresses = resolvePublicAddresses
    }

    fun bootstrapDnsHosts(bootstrapDnsHosts: List<InetAddress>?) = apply {
      this.bootstrapDnsHosts = bootstrapDnsHosts
    }

    fun bootstrapDnsHosts(vararg bootstrapDnsHosts: InetAddress): Builder =
        bootstrapDnsHosts(bootstrapDnsHosts.toList())

    fun systemDns(systemDns: Dns) = apply {
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

    internal fun isPrivateHost(host: String): Boolean {
      return PublicSuffixDatabase.get().getEffectiveTldPlusOne(host) == null
    }
  }
}
