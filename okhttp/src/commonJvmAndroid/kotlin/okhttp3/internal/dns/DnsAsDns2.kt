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
@file:OptIn(OkHttpInternalApi::class)

package okhttp3.internal.dns

import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Dns
import okhttp3.Dns2
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.concurrent.Task
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.testAndSet

/**
 * Adapts the blocking [Dns] interface to the non-blocking [Dns2] interface, using [TaskRunner] to
 * provide a thread for background execution.
 *
 * When canceled, the callback is immediately notified but the in-flight call is run to completion
 * and discarded.
 */
internal class DnsAsDns2(
  private val taskRunner: TaskRunner,
  private val delegate: Dns,
) : Dns2,
  Dns by delegate {
  override fun newCall(request: Dns2.Request): Dns2.Call = Call(request)

  override fun equals(other: Any?) = other is DnsAsDns2 && other.delegate == delegate

  override fun hashCode() = 53 * delegate.hashCode()

  override fun toString() = "DnsAsDns2($delegate)"

  private inner class Call(
    override val request: Dns2.Request,
  ) : Task("DnsAsDns2", cancelable = false),
    Dns2.Call {
    private val state = AtomicReference<State>(State.Idle)

    override fun enqueue(callback: Dns2.Callback) {
      val previous = state.testAndSet(State.Running(callback)) { it is State.Idle }
      when (previous) {
        is State.Idle -> taskRunner.newQueue().schedule(this)
        is State.Running, State.Complete -> throw IllegalStateException("already enqueued")
        State.Canceled -> callback.onFailure(this, IOException("canceled"))
      }
    }

    override fun cancel() {
      val previous = state.testAndSet(State.Canceled) { it is State.Running || it == State.Idle }
      (previous as? State.Running)?.callback?.onFailure(this, IOException("canceled"))
    }

    override fun isCanceled() = state.get() is State.Canceled

    override fun runOnce(): Long {
      try {
        val inetAddresses = delegate.lookup(request.hostname)
        val previous = state.testAndSet(State.Complete) { it is State.Running }
        (previous as? State.Running)?.callback?.onRecords(
          call = this,
          last = true,
          records = inetAddresses.map { Dns2.Record.IpAddress(request.hostname, it) },
        )
      } catch (e: UnknownHostException) {
        val previous = state.testAndSet(State.Complete) { it is State.Running }
        (previous as? State.Running)?.callback?.onFailure(
          call = this,
          e = e,
        )
      }
      return -1L
    }
  }

  private sealed interface State {
    object Idle : State

    class Running(
      val callback: Dns2.Callback,
    ) : State

    object Complete : State

    object Canceled : State
  }
}
