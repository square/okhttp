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

import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import okhttp3.internal.assertThreadDoesntHoldLock
import okhttp3.internal.okHttpName

/**
 * A set of tasks that are executed in sequential order.
 *
 * Work within queues is not concurrent. This is equivalent to each queue having a dedicated thread
 * for its work; in practice a set of queues may share a set of threads to save resources.
 */
class TaskQueue internal constructor(
  internal val taskRunner: TaskRunner,
  internal val name: String
) {
  internal var shutdown = false

  /** This queue's currently-executing task, or null if none is currently executing. */
  internal var activeTask: Task? = null

  /** Scheduled tasks ordered by [Task.nextExecuteNanoTime]. */
  internal val futureTasks = mutableListOf<Task>()

  /** True if the [activeTask] should be canceled when it completes. */
  internal var cancelActiveTask = false

  /**
   * Returns a snapshot of tasks currently scheduled for execution. Does not include the
   * currently-executing task unless it is also scheduled for future execution.
   */
  val scheduledTasks: List<Task>
    get() = synchronized(taskRunner) { futureTasks.toList() }

  /**
   * Schedules [task] for execution in [delayNanos]. A task may only have one future execution
   * scheduled. If the task is already in the queue, the earliest execution time is used.
   *
   * The target execution time is implemented on a best-effort basis. If another task in this queue
   * is running when that time is reached, that task is allowed to complete before this task is
   * started. Similarly the task will be delayed if the host lacks compute resources.
   *
   * @throws RejectedExecutionException if the queue is shut down and the task is not cancelable.
   */
  fun schedule(task: Task, delayNanos: Long = 0L) {
    synchronized(taskRunner) {
      if (shutdown) {
        if (task.cancelable) {
          taskLog(task, this) { "schedule canceled (queue is shutdown)" }
          return
        }
        taskLog(task, this) { "schedule failed (queue is shutdown)" }
        throw RejectedExecutionException()
      }

      if (scheduleAndDecide(task, delayNanos, recurrence = false)) {
        taskRunner.kickCoordinator(this)
      }
    }
  }

  /** Overload of [schedule] that uses a lambda for a repeating task. */
  inline fun schedule(
    name: String,
    delayNanos: Long = 0L,
    crossinline block: () -> Long
  ) {
    schedule(object : Task(name) {
      override fun runOnce() = block()
    }, delayNanos)
  }

  /** Executes [block] once on a task runner thread. */
  inline fun execute(
    name: String,
    delayNanos: Long = 0L,
    cancelable: Boolean = true,
    crossinline block: () -> Unit
  ) {
    schedule(object : Task(name, cancelable) {
      override fun runOnce(): Long {
        block()
        return -1L
      }
    }, delayNanos)
  }

  /** Returns a latch that reaches 0 when the queue is next idle. */
  fun idleLatch(): CountDownLatch {
    synchronized(taskRunner) {
      // If the queue is already idle, that's easy.
      if (activeTask == null && futureTasks.isEmpty()) {
        return CountDownLatch(0)
      }

      // If there's an existing AwaitIdleTask, use it. This is necessary when the executor is
      // shutdown but still busy as we can't enqueue in that case.
      val existingTask = activeTask
      if (existingTask is AwaitIdleTask) {
        return existingTask.latch
      }
      for (futureTask in futureTasks) {
        if (futureTask is AwaitIdleTask) {
          return futureTask.latch
        }
      }

      // Don't delegate to schedule() because that enforces shutdown rules.
      val newTask = AwaitIdleTask()
      if (scheduleAndDecide(newTask, 0L, recurrence = false)) {
        taskRunner.kickCoordinator(this)
      }
      return newTask.latch
    }
  }

  private class AwaitIdleTask : Task("$okHttpName awaitIdle", cancelable = false) {
    val latch = CountDownLatch(1)

    override fun runOnce(): Long {
      latch.countDown()
      return -1L
    }
  }

  /** Adds [task] to run in [delayNanos]. Returns true if the coordinator is impacted. */
  internal fun scheduleAndDecide(task: Task, delayNanos: Long, recurrence: Boolean): Boolean {
    task.initQueue(this)

    val now = taskRunner.backend.nanoTime()
    val executeNanoTime = now + delayNanos

    // If the task is already scheduled, take the earlier of the two times.
    val existingIndex = futureTasks.indexOf(task)
    if (existingIndex != -1) {
      if (task.nextExecuteNanoTime <= executeNanoTime) {
        taskLog(task, this) { "already scheduled" }
        return false
      }
      futureTasks.removeAt(existingIndex) // Already scheduled later: reschedule below!
    }
    task.nextExecuteNanoTime = executeNanoTime
    taskLog(task, this) {
      if (recurrence) "run again after ${formatDuration(executeNanoTime - now)}"
      else "scheduled after ${formatDuration(executeNanoTime - now)}"
    }

    // Insert in chronological order. Always compare deltas because nanoTime() is permitted to wrap.
    var insertAt = futureTasks.indexOfFirst { it.nextExecuteNanoTime - now > delayNanos }
    if (insertAt == -1) insertAt = futureTasks.size
    futureTasks.add(insertAt, task)

    // Impact the coordinator if we inserted at the front.
    return insertAt == 0
  }

  /**
   * Schedules immediate execution of [Task.tryCancel] on all currently-enqueued tasks. These calls
   * will not be made until any currently-executing task has completed. Tasks that return true will
   * be removed from the execution schedule.
   */
  fun cancelAll() {
    this.assertThreadDoesntHoldLock()

    synchronized(taskRunner) {
      if (cancelAllAndDecide()) {
        taskRunner.kickCoordinator(this)
      }
    }
  }

  fun shutdown() {
    this.assertThreadDoesntHoldLock()

    synchronized(taskRunner) {
      shutdown = true
      if (cancelAllAndDecide()) {
        taskRunner.kickCoordinator(this)
      }
    }
  }

  /** Returns true if the coordinator is impacted. */
  internal fun cancelAllAndDecide(): Boolean {
    if (activeTask != null && activeTask!!.cancelable) {
      cancelActiveTask = true
    }

    var tasksCanceled = false
    for (i in futureTasks.size - 1 downTo 0) {
      if (futureTasks[i].cancelable) {
        taskLog(futureTasks[i], this) { "canceled" }
        tasksCanceled = true
        futureTasks.removeAt(i)
      }
    }
    return tasksCanceled
  }

  override fun toString() = name
}
