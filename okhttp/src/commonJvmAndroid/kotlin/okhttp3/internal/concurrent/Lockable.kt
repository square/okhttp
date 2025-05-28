/*
 * Copyright (C) 2025 Block, Inc.
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
@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "NOTHING_TO_INLINE")

package okhttp3.internal.concurrent

import okhttp3.internal.assertionsEnabled

/**
 * Marker interface for objects that use the JVM's `synchronized` mechanism and th related
 * `wait()` and `notify()` functions.
 */
interface Lockable

/**
 * Returns a new anonymous object to lock on.
 *
 * Use this to encapsulate locking returned objects:
 *
 *  * So callers don't call `synchronized`, `wait()` or `notify()` on your object
 *  * So the `Lockable` interface is not in the public API
 */
@Suppress("FunctionName")
fun Lock(): Lockable =
  object : Lockable {
  }

internal inline fun Lockable.wait() = (this as Object).wait()

internal inline fun Lockable.notify() = (this as Object).notify()

internal inline fun Lockable.notifyAll() = (this as Object).notifyAll()

internal inline fun Lockable.awaitNanos(nanos: Long) {
  val ms = nanos / 1_000_000L
  val ns = nanos - (ms * 1_000_000L)
  if (ms > 0L || nanos > 0) {
    (this as Object).wait(ms, ns.toInt())
  }
}

internal inline fun Lockable.assertNotHeld() {
  if (assertionsEnabled && Thread.holdsLock(this)) {
    throw AssertionError("Thread ${Thread.currentThread().name} MUST NOT hold lock on $this")
  }
}

internal inline fun Lockable.assertHeld() {
  if (assertionsEnabled && !Thread.holdsLock(this)) {
    throw AssertionError("Thread ${Thread.currentThread().name} MUST hold lock on $this")
  }
}

internal inline fun Lockable.await() = wait()

internal inline fun Lockable.signal() = notify()

internal inline fun Lockable.signalAll() = notifyAll()

internal inline fun Lockable.assertThreadDoesntHoldLock() {
  assertNotHeld()
}

internal inline fun Lockable.assertThreadHoldsLock() {
  assertHeld()
}
