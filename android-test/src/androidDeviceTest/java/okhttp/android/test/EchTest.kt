/*
 * Copyright (C) 2025 Block, Inc.
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
package okhttp.android.test

import android.net.DnsResolver
import android.net.DnsResolver.Callback
import android.net.ssl.EchConfigList
import android.net.ssl.SSLSockets
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import javax.net.ssl.SSLSocket
import okhttp3.DelegatingSSLSocketFactory
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.platform.Platform
import okio.IOException
import org.junit.jupiter.api.Test
import org.xbill.DNS.HTTPSRecord
import org.xbill.DNS.Message
import org.xbill.DNS.SVCBBase
import org.xbill.DNS.Section.ANSWER

class EchTest {

  @Test
  fun testHttpsRequest() {
    val dns = EchDnsResolver()

    val trustManager = Platform.get().platformTrustManager()

    val sslSocketFactory = Platform.get().newSslSocketFactory(trustManager)

    val echSf = object : DelegatingSSLSocketFactory(sslSocketFactory) {
      override fun createSocket(
        socket: Socket,
        host: String,
        port: Int,
        autoClose: Boolean
      ): SSLSocket {
        return super.createSocket(socket, host, port, autoClose).also {
          val httpsRecord = dns.httpsRecords[host]?.get()
          val echConfig = httpsRecord?.getSvcParamValue(HTTPSRecord.ECH) as SVCBBase.ParameterEch?

          println("config for $host $echConfig")

          if (echConfig != null) {
            SSLSockets.setEchConfigList(
              it,
              EchConfigList.fromBytes(echConfig.data)
            )
          }
        }
      }
    }

    val client: OkHttpClient =
      OkHttpClient
        .Builder()
        .dns(dns)
        .sslSocketFactory(echSf, trustManager)
        .build()

    client.sendRequest(Request.Builder().url("https://cloudflare-ech.com/").build()) {
      println(it.body.string())
    }

    client.sendRequest(
      Request.Builder().url("https://crypto.cloudflare.com/cdn-cgi/trace").build()
    ) {
      println(it.body.string())
    }

    client.sendRequest(Request.Builder().url("https://tls-ech.dev/").build()) {
      println(it.body.string())
    }
  }

  private fun OkHttpClient.sendRequest(request: Request, fn: (Response) -> Unit = {}) {
    try {
      val response = newCall(request).execute()

      response.use {
        fn(it)
      }
    } catch (ioe: IOException) {
      ioe.printStackTrace()
    }
  }
}

class EchDnsResolver : Dns {
  val dnsResolver = DnsResolver.getInstance()

  val httpsRecords: MutableMap<String, Future<HTTPSRecord?>> = HashMap()

  override fun lookup(hostname: String): List<InetAddress> {
    val future = CompletableFuture<HTTPSRecord?>()

    val callback: Callback<ByteArray> = object : Callback<ByteArray> {
      override fun onAnswer(p0: ByteArray, p1: Int) {
        val answers = Message(p0).getSection(ANSWER)
        if (answers.isEmpty()) {
          future.complete(null)
        } else {
          future.complete(answers.single() as HTTPSRecord)
        }
      }

      override fun onError(p0: DnsResolver.DnsException) {
        future.completeExceptionally(p0)
      }
    }
    dnsResolver.rawQuery(
      null, hostname, DnsResolver.CLASS_IN, 65, DnsResolver.FLAG_EMPTY,
      { it.run() }, null,
      callback
    )
    httpsRecords[hostname] = future

    return Dns.SYSTEM.lookup(hostname)
  }
}
