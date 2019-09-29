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

/**
 * A unit of work that can be executed one or more times.
 *
 * Cancellation
 * ------------
 *
 * Tasks control their cancellation. If the hosting queue is canceled, the [Task.tryCancel] function
 * returns true if the task should skip the next-scheduled execution. Note that canceling a task is
 * not permanent; it is okay to schedule a task after it has been canceled.
 *
 * Recurrence
 * ----------
 *
 * Tasks control their recurrence schedule. The [runOnce] function returns -1L to signify that the
 * task should not be executed again. Otherwise it returns a delay until the next execution.
 *
 * A task has at most one next execution. If the same task instance is scheduled multiple times, the
 * earliest one wins. This applies to both executions scheduled with [TaskRunner.Queue.schedule] and
 * those implied by the returned execution delay.
 *
 * Task Queues
 * -----------
 *
 * Tasks are bound to the [TaskQueue] they are scheduled in. Each queue is sequential and the tasks
 * within it never execute concurrently. It is an error to use a task in multiple queues.
 */
abstract class Task(
  val name: String
) {
  // Guarded by the TaskRunner.
  internal var queue: TaskQueue? = null

  /** Undefined unless this is in [TaskQueue.futureTasks]. */
  internal var nextExecuteNanoTime = -1L

  internal var runRunnable: Runnable? = null
  internal var cancelRunnable: Runnable? = null

  /** Returns the delay in nanoseconds until the next execution, or -1L to not reschedule. */
  abstract fun runOnce(): Long

  /** Return true to skip the scheduled execution. */
  open fun tryCancel(): Boolean = false

  internal fun initQueue(queue: TaskQueue) {
    if (this.queue === queue) return

    check(this.queue === null) { "task is in multiple queues" }
    this.queue = queue

    this.runRunnable = Runnable {
      val currentThread = Thread.currentThread()
      val oldName = currentThread.name
      currentThread.name = name

      var delayNanos = -1L
      try {
        delayNanos = runOnce()
      } finally {
        queue.runCompleted(this, delayNanos)
        currentThread.name = oldName
      }
    }

    this.cancelRunnable = Runnable {
      val currentThread = Thread.currentThread()
      val oldName = currentThread.name
      currentThread.name = name

      var skipExecution = false
      try {
        skipExecution = tryCancel()
      } finally {
        queue.tryCancelCompleted(this, skipExecution)
        currentThread.name = oldName
      }
    }
  }
}
