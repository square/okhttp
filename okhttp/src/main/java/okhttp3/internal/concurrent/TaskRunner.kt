/*
 * Copyright (C) 2019 Square, Inc.
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

import okhttp3.internal.addIfAbsent
import okhttp3.internal.notify
import okhttp3.internal.threadFactory
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A set of worker threads that are shared among a set of task queues.
 *
 * The task runner is responsible for managing non-daemon threads. It keeps the process alive while
 * user-visible (ie. non-daemon) tasks are scheduled, and allows the process to exit when only
 * housekeeping (ie. daemon) tasks are scheduled.
 *
 * The task runner is also responsible for releasing held threads when the library is unloaded.
 * This is for the benefit of container environments that implement code unloading.
 *
 * Most applications should share a process-wide [TaskRunner] and use queues for per-client work.
 */
class TaskRunner(
  val backend: Backend = RealBackend()
) {
  // All state in all tasks and queues is guarded by this.

  private var coordinatorRunning = false
  private val activeQueues = mutableListOf<TaskQueue>()
  private val coordinator = Runnable { coordinate() }

  fun newQueue(owner: Any) = TaskQueue(this, owner)

  /**
   * Returns a snapshot of queues that currently have tasks scheduled. The task runner does not
   * necessarily track queues that have no tasks scheduled.
   */
  fun activeQueues(): List<TaskQueue> {
    synchronized(this) {
      return activeQueues.toList()
    }
  }

  internal fun kickCoordinator(queue: TaskQueue) {
    check(Thread.holdsLock(this))

    if (queue.isActive()) {
      activeQueues.addIfAbsent(queue)
    } else {
      activeQueues.remove(queue)
    }

    if (coordinatorRunning) {
      backend.coordinatorNotify(this)
    } else {
      coordinatorRunning = true
      backend.executeCoordinator(coordinator)
    }
  }

  private fun coordinate() {
    synchronized(this) {
      while (true) {
        val now = backend.nanoTime()
        val delayNanos = executeReadyTasks(now)

        if (delayNanos == -1L) {
          coordinatorRunning = false
          return
        }

        try {
          backend.coordinatorWait(this, delayNanos)
        } catch (_: InterruptedException) {
          // Will cause the coordinator to exit unless other tasks are scheduled!
          cancelAll()
        }
      }
    }
  }

  /**
   * Start executing the next available tasks for all queues.
   *
   * Returns the delay until the next call to this method, -1L for no further calls, or
   * [Long.MAX_VALUE] to wait indefinitely.
   */
  private fun executeReadyTasks(now: Long): Long {
    var result = -1L

    for (queue in activeQueues) {
      val delayNanos = queue.executeReadyTask(now)
      if (delayNanos == -1L) continue
      result = if (result == -1L) delayNanos else minOf(result, delayNanos)
    }

    return result
  }

  private fun cancelAll() {
    for (i in activeQueues.size - 1 downTo 0) {
      activeQueues[i].cancelAll()
    }
  }

  interface Backend {
    fun executeCoordinator(runnable: Runnable)
    fun executeTask(runnable: Runnable)
    fun nanoTime(): Long
    fun coordinatorNotify(taskRunner: TaskRunner)
    fun coordinatorWait(taskRunner: TaskRunner, nanos: Long)
  }

  internal class RealBackend : Backend {
    private val coordinatorExecutor = ThreadPoolExecutor(
        0, // corePoolSize.
        1, // maximumPoolSize.
        60L, TimeUnit.SECONDS, // keepAliveTime.
        LinkedBlockingQueue<Runnable>(),
        threadFactory("OkHttp Task Coordinator", true)
    )

    private val taskExecutor = ThreadPoolExecutor(
        0, // corePoolSize.
        Int.MAX_VALUE, // maximumPoolSize.
        60L, TimeUnit.SECONDS, // keepAliveTime.
        SynchronousQueue(),
        threadFactory("OkHttp Task", true)
    )

    override fun executeCoordinator(runnable: Runnable) {
      coordinatorExecutor.execute(runnable)
    }

    override fun executeTask(runnable: Runnable) {
      taskExecutor.execute(runnable)
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
    override fun coordinatorWait(taskRunner: TaskRunner, nanos: Long) {
      val ms = nanos / 1_000_000L
      val ns = nanos - (ms * 1_000_000L)
      if (ms > 0L || nanos > 0) {
        (taskRunner as Object).wait(ms, ns.toInt())
      }
    }

    fun shutdown() {
      coordinatorExecutor.shutdown()
      taskExecutor.shutdown()
    }
  }

  companion object {
    @JvmField
    val INSTANCE = TaskRunner(RealBackend())
  }
}
