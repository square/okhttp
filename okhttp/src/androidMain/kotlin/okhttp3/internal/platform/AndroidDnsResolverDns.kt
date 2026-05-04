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

import android.annotation.SuppressLint
import android.net.DnsResolver
import android.net.DnsResolver.Callback
import android.net.dns.HttpsEndpoint
import android.net.ssl.EchConfigList
import android.os.HandlerThread
import androidx.annotation.RequiresApi
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException
import okhttp3.Dns
import okhttp3.EchAware
import okhttp3.ech.EchConfig
import okio.ByteString
import okio.ByteString.Companion.toByteString

@Suppress("NewApi")
@RequiresApi(36)
internal class AndroidDnsResolverDns internal constructor(
  private val dnsResolver: AndroidDnsLookup = AndroidDnsResolver(),
) : Dns,
  EchAware {
  private val echConfigs = ConcurrentHashMap<String, EchConfig>()

  override fun lookup(hostname: String): List<InetAddress> {
    val result = dnsResolver.lookup(hostname)
    result.echConfig?.let {
      echConfigs[hostname] = it
    } ?: echConfigs.remove(hostname)
    return result.addresses
  }

  override fun getEchConfig(host: String): EchConfig? = echConfigs[host]
}

internal data class AndroidDnsResult(
  val addresses: List<InetAddress>,
  val echConfig: EchConfig?,
)

internal data class AndroidEchConfig(
  val echConfigList: EchConfigList,
) : EchConfig {
  @get:SuppressLint("NewApi")
  override val config: ByteString
    get() = echConfigList.toBytes().toByteString()
}

internal fun interface AndroidDnsLookup {
  @Throws(UnknownHostException::class)
  fun lookup(hostname: String): AndroidDnsResult
}

@Suppress("NewApi")
@RequiresApi(36)
internal class AndroidDnsResolver(
  private val dnsResolver: DnsResolver =
    HandlerThread("OkHttp DnsResolver").let { handlerThread ->
      handlerThread.start()
      DnsResolver(PlatformRegistry.applicationContext!!, handlerThread.looper)
    },
  private val executor: Executor = Executor { it.run() },
  private val timeoutSeconds: Long = 5L,
) : AndroidDnsLookup {
  override fun lookup(hostname: String): AndroidDnsResult {
    val endpoint = queryHttps(hostname)
    return AndroidDnsResult(
      addresses = endpoint.ipAddresses,
      echConfig = endpoint.echConfigOrNull(),
    )
  }

  private fun queryHttps(hostname: String): HttpsEndpoint =
    execute(hostname) { callback ->
      @Suppress("WrongConstant")
      dnsResolver.query(
        null,
        hostname,
        DnsResolver.FLAG_EMPTY,
        executor,
        SECONDS.toMillis(1L).toInt(),
        null,
        callback,
      )
    }

  private fun <T : Any> execute(
    hostname: String,
    query: (Callback<T>) -> Unit,
  ): T {
    val result = CompletableFuture<T>()

    query(
      object : Callback<T> {
        override fun onAnswer(
          answer: T,
          rcode: Int,
        ) {
          result.complete(answer)
        }

        override fun onError(e: DnsResolver.DnsException) {
          result.completeExceptionally(e)
        }
      },
    )

    return try {
      result.get(timeoutSeconds, SECONDS)
    } catch (e: ExecutionException) {
      throw (e.cause as? DnsResolver.DnsException)?.toUnknownHostException(hostname)
        ?: UnknownHostException("Broken system behaviour for dns lookup of $hostname").apply {
          initCause(e.cause)
        }
    } catch (e: TimeoutException) {
      throw UnknownHostException("DNS lookup timed out for $hostname").apply {
        initCause(e)
      }
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw UnknownHostException("Interrupted DNS lookup for $hostname").apply {
        initCause(e)
      }
    }
  }
}

@SuppressLint("NewApi")
private fun HttpsEndpoint.echConfigOrNull(): AndroidEchConfig? {
  val httpsRecord = httpsRecords.firstOrNull() ?: return null
  return try {
    httpsRecord.echConfigList?.let(::AndroidEchConfig)
  } catch (e: IllegalArgumentException) {
    // TODO: remove this guard when Android handles malformed or absent ECH parameters.
    // https://issuetracker.google.com/issues/319957694
    null
  }
}

@SuppressLint("NewApi")
private fun DnsResolver.DnsException.toUnknownHostException(hostname: String): UnknownHostException =
  UnknownHostException("DNS lookup failed for $hostname").apply {
    initCause(this@toUnknownHostException)
  }
