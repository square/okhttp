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
package okhttp3

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.LinkedBlockingDeque
import okhttp3.internal.concurrent.TaskFaker
import okhttp3.internal.dns.DnsAsDns2
import okio.IOException
import org.junit.jupiter.api.Test

class DnsAsDns2Test {
  private val dns = BlockingFakeDns()
  private val taskFaker = TaskFaker()
  private val dns2 =
    DnsAsDns2(
      taskRunner = taskFaker.taskRunner,
      delegate = dns,
    )
  private val callback = RecordingCallback()

  @Test
  fun `happy path`() {
    val call = dns2.newCall(Dns2.Request("lysine.dev"))
    call.enqueue(callback)

    val address1 = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    dns.put(listOf(address1))

    taskFaker.runTasks()

    assertThat(callback.events.take())
      .isEqualTo("onRecords(last=true, records=[lysine.dev/127.0.0.1])")
  }

  @Test
  fun `delegate throws`() {
    val call = dns2.newCall(Dns2.Request("lysine.dev"))
    call.enqueue(callback)

    dns.put(UnknownHostException("boom!"))

    taskFaker.runTasks()

    assertThat(callback.events.take())
      .isEqualTo("onFailure(boom!)")
  }

  @Test
  fun `call canceled before callback is enqueued`() {
    val call = dns2.newCall(Dns2.Request("lysine.dev"))
    call.cancel()
    call.enqueue(callback)

    assertThat(callback.events.take())
      .isEqualTo("onFailure(canceled)")
  }

  /** Most importantly, the cancel is delivered to the [Dns2.Callback] immediately. */
  @Test
  fun `call canceled before results are returned`() {
    val call = dns2.newCall(Dns2.Request("lysine.dev"))
    call.enqueue(callback)

    call.cancel()

    assertThat(callback.events.take())
      .isEqualTo("onFailure(canceled)")
    assertThat(callback.events.poll())
      .isNull()
  }

  @Test
  fun `call canceled after results are returned`() {
    val call = dns2.newCall(Dns2.Request("lysine.dev"))
    call.enqueue(callback)

    val address1 = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    dns.put(listOf(address1))
    taskFaker.runTasks()

    call.cancel()

    assertThat(callback.events.take())
      .isEqualTo("onRecords(last=true, records=[lysine.dev/127.0.0.1])")
    assertThat(callback.events.poll())
      .isNull()
  }

  @Test
  fun `results after cancel are not delivered`() {
    val call = dns2.newCall(Dns2.Request("lysine.dev"))
    call.enqueue(callback)

    call.cancel()

    val address1 = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    dns.put(listOf(address1))
    taskFaker.runTasks()

    assertThat(callback.events.take())
      .isEqualTo("onFailure(canceled)")
    assertThat(callback.events.poll())
      .isNull()
  }

  @Test
  fun `exception after cancel is not delivered`() {
    val call = dns2.newCall(Dns2.Request("lysine.dev"))
    call.enqueue(callback)

    call.cancel()

    dns.put(UnknownHostException("boom!"))
    taskFaker.runTasks()

    assertThat(callback.events.take())
      .isEqualTo("onFailure(canceled)")
    assertThat(callback.events.poll())
      .isNull()
  }

  class BlockingFakeDns : Dns {
    val results = LinkedBlockingDeque<Result<List<InetAddress>>>()

    override fun lookup(hostname: String): List<InetAddress> = results.take().getOrThrow()

    fun put(inetAddresses: List<InetAddress>) {
      results.put(Result.success(inetAddresses))
    }

    fun put(e: UnknownHostException) {
      results.put(Result.failure(e))
    }
  }

  class RecordingCallback : Dns2.Callback {
    val events = LinkedBlockingDeque<String>()

    override fun onRecords(
      call: Dns2.Call,
      last: Boolean,
      records: List<Dns2.Record>,
    ) {
      events.put("onRecords(last=$last, records=$records)")
    }

    override fun onFailure(
      call: Dns2.Call,
      e: IOException,
    ) {
      events.put("onFailure(${e.message})")
    }
  }
}
