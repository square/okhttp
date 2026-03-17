/*
 * Copyright (c) 2026 OkHttp Authors
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
package okhttp3.internal.platform

import android.net.DnsResolver
import android.net.DnsResolver.Callback
import androidx.annotation.RequiresApi
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import okhttp3.Dns
import okhttp3.EchAware
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.xbill.DNS.HTTPSRecord
import org.xbill.DNS.Message
import org.xbill.DNS.SVCBBase
import org.xbill.DNS.Section.ANSWER

@Suppress("NewApi")
@RequiresApi(36)
class AndroidDnsResolverDns :
  Dns,
  EchAware {
  val dnsResolver = DnsResolver.getInstance()

  val httpsRecords: MutableMap<String, Future<HTTPSRecord?>> = HashMap()

  override fun lookup(hostname: String): List<InetAddress> {
    val future = CompletableFuture<HTTPSRecord?>()

    val callback: Callback<ByteArray> =
      object : Callback<ByteArray> {
        override fun onAnswer(
          p0: ByteArray,
          p1: Int,
        ) {
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
    @Suppress("WrongConstant")
    dnsResolver.rawQuery(
      null,
      hostname,
      DnsResolver.CLASS_IN,
      65,
      DnsResolver.FLAG_EMPTY,
      { it.run() },
      null,
      callback,
    )
    httpsRecords[hostname] = future

    // TODO replace with DnsResolver call
    return Dns.SYSTEM.lookup(hostname)
  }

  override fun getHostRecords(host: String): ByteString? {
    val record = httpsRecords[host]?.get()
    val echConfig = record?.getSvcParamValue(HTTPSRecord.ECH) as SVCBBase.ParameterEch?
    return echConfig?.data?.toByteString()
  }
}
