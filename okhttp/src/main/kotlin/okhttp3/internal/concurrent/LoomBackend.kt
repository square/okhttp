/*
 * Copyright (C) 2020 Square, Inc.
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
package okhttp3.internal.concurrent

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import okhttp3.internal.SuppressSignatureCheck
import okhttp3.internal.notify

@SuppressSignatureCheck
class LoomBackend() : Backend {
  val publicLookup: MethodHandles.Lookup = MethodHandles.publicLookup()

  val mt: MethodType = MethodType.methodType(Thread::class.java, Runnable::class.java)

  val startVirtualMethod: MethodHandle =
    publicLookup.findStatic(Thread::class.java, "startVirtualThread", mt)

  override fun beforeTask(taskRunner: TaskRunner) {
  }

  override fun nanoTime() = System.nanoTime()

  override fun coordinatorNotify(taskRunner: TaskRunner) {
    taskRunner.notify()
  }

  /**
   * Wait a duration in nanoseconds. Unlike [java.lang.Object.wait] this interprets 0 as
   * "don't wait" instead of "wait forever".
   */
  @Throws(InterruptedException::class)
  @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
  override fun coordinatorWait(
    taskRunner: TaskRunner,
    nanos: Long
  ) {
    val ms = nanos / 1_000_000L
    val ns = nanos - (ms * 1_000_000L)
    if (ms > 0L || nanos > 0) {
      (taskRunner as Object).wait(ms, ns.toInt())
    }
  }

  override fun execute(runnable: Runnable) {
    // We can't call optimal invokeExact until Kotlin 1.4.
    // https://youtrack.jetbrains.com/issue/KT-14416
    startVirtualMethod.invokeWithArguments(runnable)
  }

  fun shutdown() {
  }

  companion object {
    val isSupported: Boolean =
      Thread::class.java.methods.find { it.name == "startVirtualThread" } != null
  }
}
