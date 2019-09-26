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

/**
 * A set of tasks that are executed in sequential order.
 *
 * Work within queues is not concurrent. This is equivalent to each queue having a dedicated thread
 * for its work; in practice a set of queues may share a set of threads to save resources.
 */
class TaskQueue internal constructor(
  private val taskRunner: TaskRunner,

  /**
   * An application-level object like a connection pool or HTTP call that this queue works on behalf
   * of. This is intended to be useful for testing and debugging only.
   */
  val owner: Any
) {
  /** This queue's currently-executing task, or null if none is currently executing. */
  private var activeTask: Task? = null

  /** Scheduled tasks ordered by [Task.nextExecuteNanoTime]. */
  private val futureTasks = mutableListOf<Task>()

  /** Tasks to cancel. Always either [activeTask] or a member of [futureTasks]. */
  private val cancelTasks = mutableListOf<Task>()

  internal fun isActive(): Boolean {
    check(Thread.holdsLock(taskRunner))

    return activeTask != null || futureTasks.isNotEmpty()
  }

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
   */
  fun schedule(task: Task, delayNanos: Long = 0L) {
    task.initQueue(this)

    synchronized(taskRunner) {
      if (scheduleAndDecide(task, delayNanos)) {
        taskRunner.kickCoordinator(this)
      }
    }
  }

  /** Adds [task] to run in [delayNanos]. Returns true if the coordinator should run. */
  private fun scheduleAndDecide(task: Task, delayNanos: Long): Boolean {
    val now = taskRunner.backend.nanoTime()
    val executeNanoTime = now + delayNanos

    // If the task is already scheduled, take the earlier of the two times.
    val existingIndex = futureTasks.indexOf(task)
    if (existingIndex != -1) {
      if (task.nextExecuteNanoTime <= executeNanoTime) return false // Already scheduled earlier.
      futureTasks.removeAt(existingIndex) // Already scheduled later: reschedule below!
    }
    task.nextExecuteNanoTime = executeNanoTime

    // Insert in chronological order. Always compare deltas because nanoTime() is permitted to wrap.
    var insertAt = futureTasks.indexOfFirst { it.nextExecuteNanoTime - now > delayNanos }
    if (insertAt == -1) insertAt = futureTasks.size
    futureTasks.add(insertAt, task)

    // Run the coordinator if we inserted at the front.
    return insertAt == 0
  }

  /**
   * Schedules immediate execution of [Task.tryCancel] on all currently-enqueued tasks. These calls
   * will not be made until any currently-executing task has completed. Tasks that return true will
   * be removed from the execution schedule.
   */
  fun cancelAll() {
    synchronized(taskRunner) {
      if (cancelAllAndDecide()) {
        taskRunner.kickCoordinator(this)
      }
    }
  }

  /** Returns true if the coordinator should run. */
  private fun cancelAllAndDecide(): Boolean {
    val runningTask = activeTask
    if (runningTask != null) {
      cancelTasks.addIfAbsent(runningTask)
    }

    for (task in futureTasks) {
      cancelTasks.addIfAbsent(task)
    }

    // Run the coordinator if tasks were canceled.
    return cancelTasks.isNotEmpty()
  }

  /**
   * Posts the next available task to an executor for immediate execution.
   *
   * Returns the delay until the next call to this method, -1L for no further calls, or
   * [Long.MAX_VALUE] to wait indefinitely.
   */
  internal fun executeReadyTask(now: Long): Long {
    check(Thread.holdsLock(taskRunner))

    if (activeTask != null) return Long.MAX_VALUE // This queue is busy.

    // Find a task to cancel.
    val cancelTask = cancelTasks.firstOrNull()
    if (cancelTask != null) {
      activeTask = cancelTask
      cancelTasks.removeAt(0)
      taskRunner.backend.executeTask(cancelTask.cancelRunnable!!)
      return Long.MAX_VALUE // This queue is busy until the cancel completes.
    }

    // Check if a task is immediately ready.
    val runTask = futureTasks.firstOrNull() ?: return -1L
    val delayNanos = runTask.nextExecuteNanoTime - now
    if (delayNanos <= 0) {
      activeTask = runTask
      futureTasks.removeAt(0)
      taskRunner.backend.executeTask(runTask.runRunnable!!)
      return Long.MAX_VALUE // This queue is busy until the run completes.
    }

    // Wait until the next task is ready.
    return delayNanos
  }

  internal fun runCompleted(task: Task, delayNanos: Long) {
    synchronized(taskRunner) {
      check(activeTask === task)

      if (delayNanos != -1L) {
        scheduleAndDecide(task, delayNanos)
      } else if (!futureTasks.contains(task)) {
        cancelTasks.remove(task) // We don't need to cancel it because it isn't scheduled.
      }

      activeTask = null
      taskRunner.kickCoordinator(this)
    }
  }

  internal fun tryCancelCompleted(task: Task, skipExecution: Boolean) {
    synchronized(taskRunner) {
      check(activeTask === task)

      if (skipExecution) {
        futureTasks.remove(task)
        cancelTasks.remove(task)
      }

      activeTask = null
      taskRunner.kickCoordinator(this)
    }
  }
}
