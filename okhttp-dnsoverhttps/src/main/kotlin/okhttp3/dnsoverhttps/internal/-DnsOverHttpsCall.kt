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

// TODO: in-memory caching that uses timeToLive.
// TODO: honor Https.priority and Https.targetName. Create new calls!

/**
 * Implements [Dns.Call] by making multiple HTTPS calls.
 *
 * Concurrency
 * -----------
 *
 * A few things conspire to make concurrency tricky:
 *
 *  * Each DNS record type is queried in parallel; [onResponse] and [onFailure] may be called
 *    concurrently.
 *  * Calls to [Dns.Callback] must be serialized.
 *  * We don't want to use locks to guard access to [Dns.Callback] functions.
 *
 * Each time we receive data for the callback (in the form of records or an exception), we either
 * immediately call the callback with that data (on a dispatcher thread), or queue it for the thread
 * that's busy calling the callback.
 *
 * After calling a callback, the caller must check to see if there's more data queued to deliver,
 * and deliver that also.
 *
 * The potentially surprising outcome of this strategy is the thread that performed the `TYPE_AAAA`
 * DNS request may also deliver the `TYPE_A` records to the callback, or vice versa.
 *
 * If a thread is intending to call the callback, it sets [State.Running.lockHeld] to true while
 * that call is executing.
 */
@OkHttpInternalApi
internal class DnsOverHttpsCall(
  override val request: Dns.Request,
  private val calls: List<Call>,
  private val canceledException: IOException?,
) : Dns.Call,
  Callback {
  @Volatile
  private var canceled = false
  private val state = AtomicReference<State>(State.Idle)

  override fun enqueue(callback: Dns.Callback) {
    val running =
      State.Running(
        callback = callback,
        runningCalls = calls,
      )

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
    updateStateAndCallCallbacks(
      completedCall = call,
      newException = e,
    )
  }

  override fun onResponse(
    call: Call,
    response: Response,
  ) {
    val resourceRecords =
      try {
        decodeResponse(response)
      } catch (e: IOException) {
        return updateStateAndCallCallbacks(
          completedCall = call,
          newException = e,
        )
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

    updateStateAndCallCallbacks(
      completedCall = call,
      newRecords = dnsRecords,
    )
  }

  private tailrec fun updateStateAndCallCallbacks(
    completedCall: Call? = null,
    newRecords: List<Dns.Record> = listOf(),
    newException: IOException? = null,
    lockHeldByThisThread: Boolean = false,
  ) {
    while (true) {
      val previous =
        state.get() as? State.Running
          ?: return // Already complete or canceled; nothing to do.

      val newRunningCalls =
        when {
          completedCall != null -> previous.runningCalls - completedCall
          else -> previous.runningCalls
        }

      val allExceptions =
        when {
          canceledException != null -> listOf(canceledException)
          newException != null -> previous.pendingExceptions + newException
          else -> previous.pendingExceptions
        }

      val allRecords =
        when {
          newRecords.isNotEmpty() -> previous.pendingRecords + newRecords
          else -> previous.pendingRecords
        }

      val last = newRunningCalls.isEmpty()
      val lockHeldByAnotherThread = !lockHeldByThisThread && previous.lockHeld

      // There's a few reasons why we might not call any callbacks:
      //  - There's no more records or failures to emit immediately
      //  - Another thread is already calling the callbacks.
      // In such cases, hand off any new work to that other thread and be done.
      if ((!last && allRecords.isEmpty()) || lockHeldByAnotherThread) {
        val next =
          State.Running(
            callback = previous.callback,
            runningCalls = newRunningCalls,
            lockHeld = lockHeldByAnotherThread,
            pendingRecords = allRecords,
            pendingExceptions = allExceptions,
          )
        if (!state.compareAndSet(previous, next)) continue // Lost a race, retry.
        return
      }

      // We need to call a callback. Take the lock and the records.
      val next =
        when {
          last -> {
            State.Complete
          }

          else -> {
            State.Running(
              callback = previous.callback,
              runningCalls = newRunningCalls,
              lockHeld = true,
              pendingRecords = listOf(),
              pendingExceptions = allExceptions,
            )
          }
        }
      if (!state.compareAndSet(previous, next)) continue // Lost a race, retry.

      val lastAndNoExceptions = last && allExceptions.isEmpty()
      if (allRecords.isNotEmpty() || lastAndNoExceptions) {
        previous.callback.onRecords(
          call = this,
          last = lastAndNoExceptions,
          records = allRecords,
        )
      }

      if (last && allExceptions.isNotEmpty()) {
        previous.callback.onFailure(
          call = this,
          exceptions = allExceptions,
        )
      }

      // Success, attempt to release the held lock. This might also process more events if some
      // were enqueued while the callback was executing.
      return updateStateAndCallCallbacks(
        lockHeldByThisThread = true,
      )
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
      val lockHeld: Boolean = false,
      val runningCalls: List<Call>,
      val pendingRecords: List<Dns.Record> = listOf(),
      val pendingExceptions: List<IOException> = listOf(),
    ) : State {
      init {
        check(pendingRecords.isEmpty() || lockHeld)
      }
    }

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
