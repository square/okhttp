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

import java.io.File
import java.net.UnknownHostException
import java.security.Security
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DohProviders.providers
import org.conscrypt.OpenSSLProvider

private fun runBatch(
  dnsProviders: List<DnsOverHttps>,
  names: List<String>,
) {
  var time = System.currentTimeMillis()
  for (dns in dnsProviders) {
    println("Testing ${dns.url}")
    for (host in names) {
      print("$host: ")
      System.out.flush()
      try {
        val results = dns.lookup(host)
        println(results)
      } catch (uhe: UnknownHostException) {
        var e: Throwable? = uhe
        while (e != null) {
          println(e)
          e = e.cause
        }
      }
    }
    println()
  }
  time = System.currentTimeMillis() - time
  println("Time: ${time.toDouble() / 1000} seconds\n")
}

fun main() {
  Security.insertProviderAt(OpenSSLProvider(), 1)
  var bootstrapClient = OkHttpClient()
  var names = listOf("google.com", "graph.facebook.com", "sdflkhfsdlkjdf.ee")
  try {
    println("uncached\n********\n")
    var dnsProviders =
      providers(
        client = bootstrapClient,
        http2Only = false,
        workingOnly = false,
        getOnly = false,
      )
    runBatch(dnsProviders, names)
    val dnsCache =
      Cache(
        directory = File("./target/TestDohMain.cache.${System.currentTimeMillis()}"),
        maxSize = 10L * 1024 * 1024,
      )
    println("Bad targets\n***********\n")
    val url = "https://dns.cloudflare.com/.not-so-well-known/run-dmc-query".toHttpUrl()
    val badProviders =
      listOf(
        DnsOverHttps.Builder()
          .client(bootstrapClient)
          .url(url)
          .post(true)
          .build(),
      )
    runBatch(badProviders, names)
    println("cached first run\n****************\n")
    names = listOf("google.com", "graph.facebook.com")
    bootstrapClient =
      bootstrapClient.newBuilder()
        .cache(dnsCache)
        .build()
    dnsProviders =
      providers(
        client = bootstrapClient,
        http2Only = true,
        workingOnly = true,
        getOnly = true,
      )
    runBatch(dnsProviders, names)
    println("cached second run\n*****************\n")
    dnsProviders =
      providers(
        client = bootstrapClient,
        http2Only = true,
        workingOnly = true,
        getOnly = true,
      )
    runBatch(dnsProviders, names)
  } finally {
    bootstrapClient.connectionPool.evictAll()
    bootstrapClient.dispatcher.executorService.shutdownNow()
    bootstrapClient.cache?.close()
  }
}
