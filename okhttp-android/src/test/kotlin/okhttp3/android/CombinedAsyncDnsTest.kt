/*
 * Copyright (C) 2024 Block, Inc.
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
package okhttp3.android

import assertk.all
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.index
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.prop
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Call
import okhttp3.android.RecordingAsyncDnsCallback.Event.Addresses
import okhttp3.android.RecordingAsyncDnsCallback.Event.Failure
import okhttp3.android.internal.AsyncDns
import okhttp3.android.internal.CombinedAsyncDns
import org.junit.Test

class CombinedAsyncDnsTest {
  val callback = RecordingAsyncDnsCallback()
  val addr1 = InetAddress.getByAddress("google.com", byteArrayOf(127, 0, 0, 1))
  val addr2 = InetAddress.getByAddress("google.com", byteArrayOf(127, 0, 0, 2))

  @Test
  fun empty() {
    val dns = CombinedAsyncDns(listOf())

    dns.query("google.com", null, callback)

    callback.awaitCompletion()

    assertThat(callback.events).all {
      hasSize(1)
      index(0).isInstanceOf(Failure::class).all {
        prop(Failure::hostname).isEqualTo("google.com")
        prop(Failure::hasMore).isEqualTo(false)
        prop(Failure::e).isInstanceOf(UnknownHostException::class)
      }
    }
  }

  @Test
  fun single() {
    val dns = CombinedAsyncDns(listOf(FakeAsyncDns(addr1, addr2)))

    dns.query("google.com", null, callback)

    callback.awaitCompletion()

    assertThat(callback.events).all {
      hasSize(1)
      index(0).isInstanceOf(Addresses::class).all {
        prop(Addresses::hostname).isEqualTo("google.com")
        prop(Addresses::hasMore).isEqualTo(false)
        prop(Addresses::addresses).containsExactly(addr1, addr2)
      }
    }
  }

  @Test
  fun double() {
    val dns = CombinedAsyncDns(listOf(FakeAsyncDns(addr1), FakeAsyncDns(addr2)))

    dns.query("google.com", null, callback)

    callback.awaitCompletion()

    assertThat(callback.events).all {
      hasSize(2)
      index(0).isInstanceOf(Addresses::class).all {
        prop(Addresses::hostname).isEqualTo("google.com")
        prop(Addresses::hasMore).isEqualTo(true)
        prop(Addresses::addresses).containsExactly(addr1)
      }
      index(1).isInstanceOf(Addresses::class).all {
        prop(Addresses::hostname).isEqualTo("google.com")
        prop(Addresses::hasMore).isEqualTo(false)
        prop(Addresses::addresses).containsExactly(addr2)
      }
    }
  }

  @Test
  fun failingButSucceeds() {
    val e = UnknownHostException("Unknown")
    val dns = CombinedAsyncDns(listOf(FakeAsyncDns(addr1), FailingAsyncDns(e)))

    dns.query("google.com", null, callback)

    callback.awaitCompletion()

    assertThat(callback.events).all {
      hasSize(2)
      index(0).isInstanceOf(Addresses::class).all {
        prop(Addresses::hostname).isEqualTo("google.com")
        prop(Addresses::hasMore).isEqualTo(true)
        prop(Addresses::addresses).containsExactly(addr1)
      }
      index(1).isInstanceOf(Failure::class).all {
        prop(Failure::hostname).isEqualTo("google.com")
        prop(Failure::hasMore).isEqualTo(false)
        prop(Failure::e).isInstanceOf(UnknownHostException::class)
      }
    }
  }

  @Test
  fun failingAndFails() {
    val e = UnknownHostException("Unknown")
    val dns = CombinedAsyncDns(listOf(FailingAsyncDns(e), FailingAsyncDns(e)))

    dns.query("google.com", null, callback)

    callback.awaitCompletion()

    assertThat(callback.events).all {
      hasSize(2)
      index(0).isInstanceOf(Failure::class).all {
        prop(Failure::hostname).isEqualTo("google.com")
        prop(Failure::hasMore).isEqualTo(true)
        prop(Failure::e).isInstanceOf(UnknownHostException::class)
      }
      index(1).isInstanceOf(Failure::class).all {
        prop(Failure::hostname).isEqualTo("google.com")
        prop(Failure::hasMore).isEqualTo(false)
        prop(Failure::e).isInstanceOf(UnknownHostException::class)
      }
    }
  }

  private class FakeAsyncDns(vararg val addresses: InetAddress) : AsyncDns {
    override fun query(
      hostname: String,
      originatingCall: Call?,
      callback: AsyncDns.Callback,
    ) {
      callback.onAddresses(false, hostname, addresses.toList())
    }
  }

  private class FailingAsyncDns(val e: IOException) : AsyncDns {
    override fun query(
      hostname: String,
      originatingCall: Call?,
      callback: AsyncDns.Callback,
    ) {
      callback.onFailure(false, hostname, e)
    }
  }
}
