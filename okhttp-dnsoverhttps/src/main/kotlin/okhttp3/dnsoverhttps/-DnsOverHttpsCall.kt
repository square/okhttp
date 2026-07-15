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
@file:Suppress("ktlint:standard:filename")
@file:OptIn(OkHttpInternalApi::class)

package okhttp3.dnsoverhttps

import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns2
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.testAndSet

// TODO: honor resolvePrivateAddresses, resolvePublicAddresses
// TODO: in-memory caching that uses timeToLive.
// TODO: honor Https.priority and Https.targetName. Create new calls!

/**
 * Implements [Dns2.Call] by making multiple HTTPS calls.
 */
internal class DnsOverHttpsCall(
  private val dnsOverHttps: DnsOverHttps,
  override val request: Dns2.Request,
) : Dns2.Call,
  Callback {
  private val state = AtomicReference<State>(State.Idle)

  override fun enqueue(callback: Dns2.Callback) {
    val calls =
      buildList {
        if (dnsOverHttps.includeHttps) {
          add(dnsOverHttps.createCall(request.hostname, TYPE_HTTPS))
        }

        add(dnsOverHttps.createCall(request.hostname, TYPE_A))

        if (dnsOverHttps.includeIPv6) {
          add(dnsOverHttps.createCall(request.hostname, TYPE_AAAA))
        }
      }

    val running = State.Running(callback, calls)

    val previous = state.testAndSet(running) { it is State.Idle }
    when (previous) {
      is State.Idle -> {
        for (call in calls) {
          call.enqueue(this)
        }
      }

      is State.Running, State.Complete -> {
        throw IllegalStateException("already enqueued")
      }

      State.Canceled -> {
        callback.onFailure(this, IOException("canceled"))
      }
    }
  }

  /**
   * If this is the last DNS call, call [Callback.onFailure]. Otherwise, hold that call until the
   * last DNS call completes.
   */
  override fun onFailure(
    call: Call,
    e: IOException,
  ) {
    while (true) {
      val previous =
        state.get() as? State.Running
          ?: return // Already complete or canceled; nothing to do.

      val newRunningCalls = previous.runningCalls - call
      val allFailures = previous.delayedFailures + e
      val next =
        when {
          newRunningCalls.isEmpty() -> {
            State.Complete
          }

          else -> {
            State.Running(
              callback = previous.callback,
              runningCalls = newRunningCalls,
              delayedFailures = allFailures,
            )
          }
        }

      if (!state.compareAndSet(previous, next)) continue // Lost a race; retry.

      if (next is State.Complete) {
        previous.callback.onFailure(
          call = this,
          exceptions = allFailures,
        )
      }

      return
    }
  }

  override fun onResponse(
    call: Call,
    response: Response,
  ) {
    val resourceRecords =
      try {
        dnsOverHttps.decodeResponse(response)
      } catch (e: IOException) {
        return onFailure(call, e)
      }

    val dns2Records =
      resourceRecords.map { resourceRecord ->
        when (resourceRecord) {
          is ResourceRecord.Https -> {
            Dns2.Record.ServiceMetadata(
              hostname = request.hostname,
              alpnIds =
                resourceRecord.alpnIds?.mapNotNull { alpnId ->
                  try {
                    Protocol.get(alpnId)
                  } catch (_: IOException) {
                    null // Skip unrecognized ALPN ID.
                  }
                },
              port = resourceRecord.port,
              ipAddressHints = resourceRecord.ipAddressHints,
              echConfigList = resourceRecord.echConfigList,
            )
          }

          is ResourceRecord.IpAddress -> {
            Dns2.Record.IpAddress(
              hostname = request.hostname,
              address = resourceRecord.address,
            )
          }
        }
      }

    while (true) {
      val previous =
        state.get() as? State.Running
          ?: return // Already complete or canceled; nothing to do.

      val newRunningCalls = previous.runningCalls - call
      val next =
        when {
          newRunningCalls.isEmpty() -> {
            State.Complete
          }

          else -> {
            State.Running(
              callback = previous.callback,
              runningCalls = newRunningCalls,
              delayedFailures = previous.delayedFailures,
            )
          }
        }

      if (!state.compareAndSet(previous, next)) continue // Lost a race; retry.

      previous.callback.onRecords(
        call = this,
        last = newRunningCalls.isEmpty(),
        records = dns2Records,
      )

      return
    }
  }

  override fun cancel() {
    val previous = state.testAndSet(State.Canceled) { it is State.Running || it == State.Idle }
    (previous as? State.Running)?.callback?.onFailure(this, IOException("canceled"))
  }

  override fun isCanceled() = state.get() is State.Canceled

  private sealed interface State {
    object Idle : State

    class Running(
      val callback: Dns2.Callback,
      val runningCalls: List<Call>,
      val delayedFailures: List<IOException> = listOf(),
    ) : State

    object Complete : State

    object Canceled : State
  }
}

internal fun Dns2.Callback.onFailure(
  call: DnsOverHttpsCall,
  exceptions: List<IOException>,
) {
  val firstException = exceptions.first()
  for (i in 1 until exceptions.size) {
    firstException.addSuppressed(exceptions[i])
  }

  onFailure(call, firstException)
}
