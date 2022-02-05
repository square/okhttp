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
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
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
import okhttp3.internal.dns.AsyncDns
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
  @get:JvmName("post") val post: Boolean,
  @get:JvmName("resolvePrivateAddresses") val resolvePrivateAddresses: Boolean,
  @get:JvmName("resolvePublicAddresses") val resolvePublicAddresses: Boolean
) : Dns, AsyncDns {
  @Throws(UnknownHostException::class)
  override fun lookup(hostname: String): List<InetAddress> {
    checkForPrivateAddress(hostname)

    return lookupHttps(hostname)
  }

  private fun checkForPrivateAddress(hostname: String) {
    if (!resolvePrivateAddresses || !resolvePublicAddresses) {
      val privateHost = isPrivateHost(hostname)

      if (privateHost && !resolvePrivateAddresses) {
        throw UnknownHostException("private hosts not resolved")
      }

      if (!privateHost && !resolvePublicAddresses) {
        throw UnknownHostException("public hosts not resolved")
      }
    }
  }

  @Throws(UnknownHostException::class)
  private fun lookupHttps(hostname: String): List<InetAddress> {
    val networkRequests = ArrayList<Call>(2)
    val failures = ArrayList<Exception>(2)
    val results = ArrayList<InetAddress>(5)

    val resultFn: (List<InetAddress>) -> Unit = { addresses ->
      synchronized(results) {
        results.addAll(addresses)
      }
    }
    val failuresFn: (Exception) -> Unit = { e ->
      synchronized(failures) {
        failures.add(e)
      }
    }
    val networkRequestsFn: (AsyncDns.DnsClass, Call) -> Unit = { _, call ->
      networkRequests.add(call)
    }

    buildRequest(
      hostname = hostname,
      networkRequests = networkRequestsFn,
      results = { _, addresses -> resultFn(addresses) },
      failures = { _, e -> failuresFn(e) },
      dnsClass = AsyncDns.DnsClass.IPV4
    )

    if (includeIPv6) {
      buildRequest(
        hostname = hostname,
        networkRequests = networkRequestsFn,
        results = { _, addresses -> resultFn(addresses) },
        failures = { _, e -> failuresFn(e) },
        dnsClass = AsyncDns.DnsClass.IPV6
      )
    }

    executeRequests(hostname, networkRequests, { resultFn(it) }, { failuresFn(it) })

    return results.ifEmpty {
      throwBestFailure(hostname, failures)
    }
  }

  override fun query(hostname: String, callback: AsyncDns.Callback) {
    val networkRequests = ArrayList<Pair<AsyncDns.DnsClass, Call>>(2)

    val resultFn: (AsyncDns.DnsClass, List<InetAddress>) -> Unit = { dnsClass, results ->
      callback.onResults(dnsClass, results)
    }
    val failuresFn: (AsyncDns.DnsClass, Exception) -> Unit = { dnsClass, e ->
      callback.onError(dnsClass, e)
    }
    val networkRequestsFn: (AsyncDns.DnsClass, Call) -> Unit = { dnsClass, call ->
      networkRequests.add(Pair(dnsClass, call))
    }

    buildRequest(hostname, networkRequestsFn, resultFn, failuresFn, AsyncDns.DnsClass.IPV4)

    if (includeIPv6) {
      buildRequest(hostname, networkRequestsFn, resultFn, failuresFn, AsyncDns.DnsClass.IPV6)
    }

    val executing = AtomicInteger(networkRequests.size)

    networkRequests.forEach { (dnsClass, call) ->
      call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
          failuresFn(dnsClass, e)

          synchronized(executing) {
            if (executing.decrementAndGet() == 0) {
              callback.onComplete()
            }
          }
        }

        override fun onResponse(call: Call, response: Response) {
          processResponse(
            response = response,
            hostname = hostname,
            results = { resultFn(dnsClass, it) },
            failures = { failuresFn(dnsClass, it) }
          )

          synchronized(executing) {
            if (executing.decrementAndGet() == 0) {
              callback.onComplete()
            }
          }
        }
      })
    }
  }

  private fun buildRequest(
    hostname: String,
    networkRequests: (AsyncDns.DnsClass, Call) -> Unit,
    results: (AsyncDns.DnsClass, List<InetAddress>) -> Unit,
    failures: (AsyncDns.DnsClass, Exception) -> Unit,
    dnsClass: AsyncDns.DnsClass
  ) {
    val request = buildRequest(hostname, dnsClass)
    val response = getCacheOnlyResponse(request)

    response?.let { cacheResponse ->
      processResponse(
        cacheResponse, hostname, { results(dnsClass, it) }, { failures(dnsClass, it) })
    } ?: networkRequests(dnsClass, client.newCall(request))
  }

  private fun executeRequests(
    hostname: String,
    networkRequests: List<Call>,
    results: (List<InetAddress>) -> Unit,
    failures: (Exception) -> Unit,
  ) {
    val latch = CountDownLatch(networkRequests.size)

    for (call in networkRequests) {
      call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
          failures(e)
          latch.countDown()
        }

        override fun onResponse(call: Call, response: Response) {
          processResponse(response, hostname, results, failures)
          latch.countDown()
        }
      })
    }

    try {
      latch.await()
    } catch (e: InterruptedException) {
      failures(e)
    }
  }

  private fun processResponse(
    response: Response,
    hostname: String,
    results: (List<InetAddress>) -> Unit,
    failures: (Exception) -> Unit,
  ) {
    try {
      val addresses = readResponse(hostname, response)
      results(addresses)
    } catch (e: Exception) {
      failures(e)
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
        // Use the cache without hitting the network first
        // 504 code indicates that the Cache is stale
        val preferCache = CacheControl.Builder()
          .onlyIfCached()
          .build()
        val cacheRequest = request.newBuilder().cacheControl(preferCache).build()

        val cacheResponse = client.newCall(cacheRequest).execute()

        if (cacheResponse.code != HttpURLConnection.HTTP_GATEWAY_TIMEOUT) {
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

  private fun buildRequest(hostname: String, dnsClass: AsyncDns.DnsClass): Request =
    Request.Builder().header("Accept", DNS_MESSAGE.toString()).apply {
      val query = DnsRecordCodec.encodeQuery(hostname, dnsClass.type)

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
