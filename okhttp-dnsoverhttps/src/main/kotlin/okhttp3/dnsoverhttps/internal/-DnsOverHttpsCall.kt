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

package okhttp3.dnsoverhttps.internal

import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.testAndSet

// TODO: honor resolvePrivateAddresses, resolvePublicAddresses
// TODO: in-memory caching that uses timeToLive.
// TODO: honor Https.priority and Https.targetName. Create new calls!

/**
 * Implements [Dns.Call] by making multiple HTTPS calls.
 */
@OkHttpInternalApi
internal class DnsOverHttpsCall(
  override val request: Dns.Request,
  private val calls: List<Call>,
  private val canceledException: IOException?,
) : Dns.Call,
  Callback {
  @Volatile private var canceled = false
  private val state = AtomicReference<State>(State.Idle)

  override fun enqueue(callback: Dns.Callback) {
    val running = State.Running(callback, calls)

    val previous = state.testAndSet(running) { it is State.Idle }
    check(previous is State.Idle) {
      "already enqueued"
    }

    for (call in calls) {
      call.enqueue(this)
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
      val allFailures =
        when {
          canceledException != null -> listOf(canceledException)
          else -> previous.delayedFailures + e
        }
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
        decodeResponse(response)
      } catch (e: IOException) {
        return onFailure(call, e)
      }

    val dnsRecords =
      resourceRecords.map { resourceRecord ->
        when (resourceRecord) {
          is ResourceRecord.Https -> {
            Dns.Record.ServiceMetadata(
              hostname = resourceRecord.targetName.takeIf { it != "" } ?: request.hostname,
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
            Dns.Record.IpAddress(
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
      val lastRunningCall = newRunningCalls.isEmpty()
      val next =
        when {
          lastRunningCall -> {
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

      val emitDelayedFailures = lastRunningCall && previous.delayedFailures.isNotEmpty()
      val lastEvent = lastRunningCall && !emitDelayedFailures
      if (dnsRecords.isNotEmpty() || lastEvent) {
        previous.callback.onRecords(
          call = this,
          last = lastEvent,
          records = dnsRecords,
        )
      }
      if (emitDelayedFailures) {
        previous.callback.onFailure(
          call = this,
          exceptions = previous.delayedFailures,
        )
      }

      return
    }
  }

  override fun cancel() {
    if (canceled) return // Already canceled.

    canceled = true
    for (call in calls) {
      call.cancel()
    }
  }

  override fun isCanceled() = canceled

  private sealed interface State {
    object Idle : State

    class Running(
      val callback: Dns.Callback,
      val runningCalls: List<Call>,
      val delayedFailures: List<IOException> = listOf(),
    ) : State

    object Complete : State
  }
}

internal fun Dns.Callback.onFailure(
  call: DnsOverHttpsCall,
  exceptions: List<IOException>,
) {
  val firstException = exceptions.first()
  for (i in 1 until exceptions.size) {
    firstException.addSuppressed(exceptions[i])
  }

  onFailure(call, firstException)
}
