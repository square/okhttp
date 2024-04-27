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
@file:OptIn(ExperimentalContracts::class)

package okhttp3.internal.connection

import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTimedValue
import okhttp3.Dispatcher
import okhttp3.internal.assertHeld
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
    return lock.withMonitoredLock(action)
  }

  inline fun <T> RealConnection.withLock(action: () -> T): T {
    contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
    return lock.withMonitoredLock(action)
  }

  inline fun <T> RealCall.withLock(action: () -> T): T {
    contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
    return lock.withMonitoredLock(action)
  }

  inline fun <T> Http2Connection.withLock(action: () -> T): T {
    contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
    return lock.withMonitoredLock(action)
  }

  inline fun <T> Http2Stream.withLock(action: () -> T): T {
    contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
    return lock.withMonitoredLock(action)
  }

  inline fun <T> TaskRunner.withLock(action: () -> T): T {
    contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
    return lock.withMonitoredLock(action)
  }

  inline fun <T> TaskQueue.withLock(action: () -> T): T {
    contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
    return lock.withMonitoredLock(action)
  }

  inline fun <T> Http2Writer.withLock(action: () -> T): T {
    // TODO can we assert we don't have the connection lock?

    contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
    return lock.withMonitoredLock(action)
  }

  internal fun ReentrantLock.newLockCondition(): Condition {
    val condition = this.newCondition()
    return object : Condition by condition {
      override fun await() {
        assertHeld()
        return timeAwait { condition.await() }
      }

      override fun await(
        time: Long,
        unit: TimeUnit?,
      ): Boolean {
        assertHeld()
        return timeAwait { condition.await(time, unit) }
      }

      override fun awaitUninterruptibly() {
        assertHeld()
        return timeAwait { condition.awaitUninterruptibly() }
      }

      override fun awaitNanos(nanosTimeout: Long): Long {
        assertHeld()
        return timeAwait { condition.awaitNanos(nanosTimeout) }
      }

      override fun awaitUntil(deadline: Date): Boolean {
        assertHeld()
        return timeAwait { condition.awaitUntil(deadline) }
      }
    }
  }

  private fun <T> ReentrantLock.timeAwait(function: () -> T): T {
    return if (this == lockToWatch) {
      measureTimedValue { function() }.also {
        val lockDuration = it.duration
//        if (lockDuration > 1.milliseconds) {
//          println(Thread.currentThread().name + " await " + lockDuration)
//          Exception().printStackTrace()
          threadLocalAwait.set(threadLocalAwait.get() + lockDuration)
//        }
      }.value
    } else {
      function()
    }
  }

  inline fun <T> ReentrantLock.withMonitoredLock(action: () -> T): T {
    contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
    return if (this == lockToWatch) {
      withLock {
        measureTimedValue {
          action()
        }
      }.also {
        val awaitDuration = threadLocalAwait.get()
        threadLocalAwait.remove()
        if (it.duration - awaitDuration > 1.milliseconds) {
          println(Thread.currentThread().name + " lock " + it.duration + " " + awaitDuration)
//          Exception().printStackTrace()
        }
      }.value
    } else {
      withLock(action)
    }
  }

  @Suppress("NewApi")
  val threadLocalAwait = ThreadLocal.withInitial { Duration.ZERO }

  @Volatile
  var lockToWatch: ReentrantLock? = null
}
