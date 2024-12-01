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
@file:OptIn(ExperimentalContracts::class, ExperimentalContracts::class)

package okhttp3.internal.connection

import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import okhttp3.Dispatcher
import okhttp3.internal.concurrent.TaskQueue
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.http2.Http2Connection
import okhttp3.internal.http2.Http2Stream
import okhttp3.internal.http2.Http2Writer

/**
 * Centralisation of central locks according to docs/contribute/concurrency.md
 */
internal object Locks {
  inline fun <T> Dispatcher.withLock(action: () -> T): T {
    contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
    return lock.runWithLock(action)
  }

  inline fun <T> RealConnection.withLock(action: () -> T): T {
    contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
    return lock.runWithLock(action)
  }

  inline fun <T> RealCall.withLock(action: () -> T): T {
    contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
    return lock.runWithLock(action)
  }

  inline fun <T> Http2Connection.withLock(action: () -> T): T {
    contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
    return lock.runWithLock(action)
  }

  inline fun <T> Http2Stream.withLock(action: () -> T): T {
    contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
    return lock.runWithLock(action)
  }

  inline fun <T> TaskRunner.withLock(action: () -> T): T {
    contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
    return lock.runWithLock(action)
  }

  inline fun <T> TaskQueue.withLock(action: () -> T): T {
    contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
    return lock.runWithLock(action)
  }

  inline fun <T> Http2Writer.withLock(action: () -> T): T {
    // TODO can we assert we don't have the connection lock?

    contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
    return lock.runWithLock(action)
  }

  /**
   * A no cost (inlined) alias to [ReentrantLock#newCondition] for an OkHttp Lock.
   * No function on its own but places a central place that all conditions go through to allow
   * temporary debugging.
   */
  internal fun ReentrantLock.newLockCondition(): Condition {
    return this.newCondition()
  }

  /**
   * A no cost (inlined) alias to [ReentrantLock#withLock] for an OkHttp Lock.
   * No function on its own but places a central place that all locks go through to allow
   * temporary debugging.
   */
  inline fun <T> ReentrantLock.runWithLock(action: () -> T): T {
    contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
    return withLock(action)
  }
}
