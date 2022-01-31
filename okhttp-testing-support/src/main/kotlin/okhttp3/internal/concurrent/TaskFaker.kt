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

import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat

/**
 * Runs a [TaskRunner] in a controlled environment so that everything is sequential and
 * deterministic.
 *
 * This class ensures that at most one thread is running at a time. This is initially the JUnit test
 * thread, which temporarily shares its execution privilege by calling [runTasks], [runNextTask], or
 * [advanceUntil]. These methods wait for its task threads to stop executing before it returns.
 *
 * Task threads stall execution in these ways:
 *
 *  * By being ready to start. Newly-created tasks don't run immediately.
 *  * By finishing their work. This occurs when [Runnable.run] returns.
 *  * By requesting to wait by calling [TaskRunner.Backend.coordinatorWait].
 *
 * Most test methods start by unblocking task threads, then wait for those task threads to stall
 * again before returning.
 */
class TaskFaker {
  @Suppress("NOTHING_TO_INLINE")
  internal inline fun Any.assertThreadHoldsLock() {
    if (assertionsEnabled && !Thread.holdsLock(this)) {
      throw AssertionError("Thread ${Thread.currentThread().name} MUST hold lock on $this")
    }
  }

  @Suppress("NOTHING_TO_INLINE")
  internal inline fun Any.assertThreadDoesntHoldLock() {
    if (assertionsEnabled && Thread.holdsLock(this)) {
      throw AssertionError("Thread ${Thread.currentThread().name} MUST NOT hold lock on $this")
    }
  }

  @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "NOTHING_TO_INLINE")
  internal inline fun Any.wait() = (this as Object).wait()

  @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "NOTHING_TO_INLINE")
  internal inline fun Any.notifyAll() = (this as Object).notifyAll()

  val logger = Logger.getLogger("TaskFaker." + instance++)

  /** Though this executor service may hold many threads, they are not executed concurrently. */
  private val tasksExecutor = Executors.newCachedThreadPool()

  /** The number of runnables known to [tasksExecutor]. Guarded by [taskRunner]. */
  private var tasksRunningCount = 0

  /**
   * Threads in this list are waiting for either [interruptCoordinatorThread] or [notifyAll].
   * Guarded by [taskRunner].
   */
  private val stalledTasks = mutableListOf<Thread>()

  /**
   * Released whenever a thread is either added to [stalledTasks] or completes. The test thread
   * uses this to wait until all subject threads complete or stall.
   */
  private val taskBecameStalled = Semaphore(0)

  /**
   * True if this task faker has ever had multiple tasks scheduled to run concurrently. Guarded by
   * [taskRunner].
   */
  var isParallel = false

  /** Guarded by [taskRunner]. */
  var nanoTime = 0L
    private set

  /** The thread currently waiting for time to advance. Guarded by [taskRunner]. */
  private var waitingCoordinatorThread: Thread? = null

  /** A task runner that posts tasks to this fake. Tasks won't be executed until requested. */
  val taskRunner: TaskRunner = TaskRunner(object : TaskRunner.Backend {
    override fun execute(taskRunner: TaskRunner, runnable: Runnable) {
      taskRunner.assertThreadHoldsLock()
      val acquiredTaskRunnerLock = AtomicBoolean()

      tasksExecutor.execute {
        synchronized(taskRunner) {
          acquiredTaskRunnerLock.set(true)
          taskRunner.notifyAll()

          tasksRunningCount++
          if (tasksRunningCount > 1) isParallel = true
          try {
            stall(taskRunner)
            runnable.run()
          } finally {
            tasksRunningCount--
            taskBecameStalled.release()
          }
        }
      }

      // Execute() must not return until the launched task reaches stall().
      while (!acquiredTaskRunnerLock.get()) {
        taskRunner.wait()
      }
    }

    override fun nanoTime() = nanoTime

    override fun coordinatorNotify(taskRunner: TaskRunner) {
      taskRunner.assertThreadHoldsLock()
      check(waitingCoordinatorThread != null)

      stalledTasks.remove(waitingCoordinatorThread)
      taskRunner.notifyAll()
    }

    override fun coordinatorWait(taskRunner: TaskRunner, nanos: Long) {
      taskRunner.assertThreadHoldsLock()

      check(waitingCoordinatorThread == null)
      if (nanos == 0L) return

      waitingCoordinatorThread = Thread.currentThread()
      try {
        stall(taskRunner)
      } finally {
        waitingCoordinatorThread = null
      }
    }

    /** Wait for the test thread to proceed. */
    private fun stall(taskRunner: TaskRunner) {
      taskRunner.assertThreadHoldsLock()

      val currentThread = Thread.currentThread()
      taskBecameStalled.release()
      stalledTasks += currentThread
      try {
        while (currentThread in stalledTasks) {
          taskRunner.wait()
        }
      } catch (e: InterruptedException) {
        stalledTasks.remove(currentThread)
        throw e
      }
    }
  }, logger = logger)

  /** Runs all tasks that are ready. Used by the test thread only. */
  fun runTasks() {
    advanceUntil(nanoTime)
  }

  /** Advance the simulated clock, then runs tasks that are ready. Used by the test thread only. */
  fun advanceUntil(newTime: Long) {
    taskRunner.assertThreadDoesntHoldLock()

    synchronized(taskRunner) {
      nanoTime = newTime
      stalledTasks.clear()
      taskRunner.notifyAll()
    }

    waitForTasksToStall()
  }

  private fun waitForTasksToStall() {
    taskRunner.assertThreadDoesntHoldLock()

    while (true) {
      synchronized(taskRunner) {
        if (tasksRunningCount == stalledTasks.size) {
          return@waitForTasksToStall // All stalled.
        }
        taskBecameStalled.drainPermits()
      }
      taskBecameStalled.acquire()
    }
  }

  /** Confirm all tasks have completed. Used by the test thread only. */
  fun assertNoMoreTasks() {
    taskRunner.assertThreadDoesntHoldLock()

    synchronized(taskRunner) {
      assertThat(stalledTasks).isEmpty()
    }
  }

  /** Unblock a waiting task thread. Used by the test thread only. */
  fun interruptCoordinatorThread() {
    taskRunner.assertThreadDoesntHoldLock()

    // Make sure the coordinator is ready to be interrupted.
    runTasks()

    synchronized(taskRunner) {
      val toInterrupt = waitingCoordinatorThread ?: error("no thread currently waiting")
      taskBecameStalled.drainPermits()
      toInterrupt.interrupt()
    }

    waitForTasksToStall()
  }

  /** Ask a single task to proceed. Used by the test thread only. */
  fun runNextTask() {
    taskRunner.assertThreadDoesntHoldLock()

    synchronized(taskRunner) {
      check(stalledTasks.size >= 1) { "no tasks to run" }
      stalledTasks.removeFirst()
      taskRunner.notifyAll()
    }

    waitForTasksToStall()
  }

  /** Returns true if no tasks have been scheduled. This runs the coordinator for confirmation. */
  fun isIdle() = taskRunner.activeQueues().isEmpty()

  companion object {
    var instance = 0

    @JvmField
    val assertionsEnabled: Boolean = OkHttpClient::class.java.desiredAssertionStatus()
  }
}
