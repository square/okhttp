/*
 * Copyright (C) 2022 Square, Inc.
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
import java.util.concurrent.CountDownLatch
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.internal.dns.AsyncDns
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

fun main() {
  val cacheFs = FakeFileSystem()

  val bootstrapClient = OkHttpClient.Builder()
    .cache(Cache("dnscache".toPath(), 10_000_000, cacheFs))
    .build()

  val dohClient = DohProviders.buildGoogle(bootstrapClient)

  val latch = CountDownLatch(1)

  dohClient.query("google.com", object: AsyncDns.Callback {
    override fun onResults(dnsClass: AsyncDns.DnsClass, addresses: List<InetAddress>) {
      println("results $dnsClass $addresses")
    }

    override fun onComplete() {
      println("complete")
      latch.countDown()
    }

    override fun onError(dnsClass: AsyncDns.DnsClass, e: Exception) {
      println("error $dnsClass $e")
    }
  })

  latch.await()

  dohClient.client.dispatcher.executorService.shutdown()
}
