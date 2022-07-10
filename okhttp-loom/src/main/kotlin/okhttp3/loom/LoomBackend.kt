/*
 * Copyright (C) 2022 Square, Inc.
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
package okhttp3.loom

import java.util.concurrent.BlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import okhttp3.OkHttpClient
import okhttp3.internal.concurrent.TaskRunner

/**
 * May not be needed, if doesn't change from real backend.
 */
class LoomBackend(
  internal val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
) : TaskRunner.Backend {
  override fun nanoTime(): Long = System.nanoTime()

  override fun coordinatorNotify(taskRunner: TaskRunner) {
    taskRunner.lock.assertThreadHolds()
    taskRunner.condition.signal()
  }

  /**
   * Wait a duration in nanoseconds. Unlike [java.lang.Object.wait] this interprets 0 as
   * "don't wait" instead of "wait forever".
   */
  @Throws(InterruptedException::class)
  @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
  override fun coordinatorWait(taskRunner: TaskRunner, nanos: Long) {
    taskRunner.lock.assertThreadHolds()
    if (nanos > 0) {
      taskRunner.condition.awaitNanos(nanos)
    }
  }

  override fun <T> decorate(queue: BlockingQueue<T>) = queue

  override fun execute(taskRunner: TaskRunner, runnable: Runnable) {
    executor.execute(runnable)
  }
}

@JvmField
internal val assertionsEnabled: Boolean = OkHttpClient::class.java.desiredAssertionStatus()

@Suppress("NOTHING_TO_INLINE")
internal inline fun ReentrantLock.assertThreadHolds() {
  if (assertionsEnabled && !this.isHeldByCurrentThread) {
    throw AssertionError("Thread ${Thread.currentThread().name} MUST hold lock on $this")
  }
}
