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

import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import java.io.Closeable
import java.util.AbstractQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import kotlin.concurrent.withLock

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
 *  * By waiting until the simulated clock reaches a specific time.
 *  * By requesting to wait by calling [TaskRunner.Backend.coordinatorWait].
 *
 * Most test methods start by unblocking task threads, then wait for those task threads to stall
 * again before returning.
 */
class TaskFaker : Closeable {
  @Suppress("NOTHING_TO_INLINE")
  internal inline fun Any.assertThreadHoldsLock() {
    if (assertionsEnabled && !taskRunner.lock.isHeldByCurrentThread) {
      throw AssertionError("Thread ${Thread.currentThread().name} MUST hold lock on $this")
    }
  }

  @Suppress("NOTHING_TO_INLINE")
  internal inline fun Any.assertThreadDoesntHoldLock() {
    if (assertionsEnabled && taskRunner.lock.isHeldByCurrentThread) {
      throw AssertionError("Thread ${Thread.currentThread().name} MUST NOT hold lock on $this")
    }
  }

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

  /** True if new tasks should run immediately without stalling. Guarded by [taskRunner]. */
  private var isRunningAllTasks = false

  /** A task runner that posts tasks to this fake. Tasks won't be executed until requested. */
  val taskRunner: TaskRunner = TaskRunner(object : TaskRunner.Backend {
    override fun execute(taskRunner: TaskRunner, runnable: Runnable) {
      taskRunner.assertThreadHoldsLock()
      val acquiredTaskRunnerLock = AtomicBoolean()

      tasksExecutor.execute {
        taskRunner.lock.withLock {
          acquiredTaskRunnerLock.set(true)
          taskRunner.condition.signalAll()

          tasksRunningCount++
          if (tasksRunningCount > 1) isParallel = true
          try {
            if (!isRunningAllTasks) {
              stall()
            }
            runnable.run()
          } catch (e: InterruptedException) {
            if (!tasksExecutor.isShutdown) throw e // Ignore shutdown-triggered interruptions.
          } finally {
            tasksRunningCount--
            taskBecameStalled.release()
          }
        }
      }

      // Execute() must not return until the launched task stalls.
      while (!acquiredTaskRunnerLock.get()) {
        taskRunner.condition.await()
      }
    }

    override fun nanoTime() = nanoTime

    override fun coordinatorNotify(taskRunner: TaskRunner) {
      taskRunner.assertThreadHoldsLock()
      check(waitingCoordinatorThread != null)

      stalledTasks.remove(waitingCoordinatorThread)
      taskRunner.condition.signalAll()
    }

    override fun coordinatorWait(taskRunner: TaskRunner, nanos: Long) {
      taskRunner.assertThreadHoldsLock()

      check(waitingCoordinatorThread == null)
      if (nanos == 0L) return

      waitingCoordinatorThread = Thread.currentThread()
      try {
        stall()
      } finally {
        waitingCoordinatorThread = null
      }
    }

    override fun <T> decorate(queue: BlockingQueue<T>) = TaskFakerBlockingQueue(queue)
  }, logger = logger)

  /** Wait for the test thread to proceed. */
  private fun stall() {
    taskRunner.assertThreadHoldsLock()

    val currentThread = Thread.currentThread()
    taskBecameStalled.release()
    stalledTasks += currentThread
    try {
      while (currentThread in stalledTasks) {
        taskRunner.condition.await()
      }
    } catch (e: InterruptedException) {
      stalledTasks.remove(currentThread)
      throw e
    }
  }

  private fun unstallTasks() {
    taskRunner.assertThreadHoldsLock()

    stalledTasks.clear()
    taskRunner.condition.signalAll()
  }

  /** Runs all tasks that are ready. Used by the test thread only. */
  fun runTasks() {
    advanceUntil(nanoTime)
  }

  /** Advance the simulated clock, then runs tasks that are ready. Used by the test thread only. */
  fun advanceUntil(newTime: Long) {
    taskRunner.assertThreadDoesntHoldLock()

    taskRunner.lock.withLock {
      isRunningAllTasks = true
      nanoTime = newTime
      unstallTasks()
    }

    waitForTasksToStall()
  }

  private fun waitForTasksToStall() {
    taskRunner.assertThreadDoesntHoldLock()

    while (true) {
      taskRunner.lock.withLock {
        if (tasksRunningCount == stalledTasks.size) {
          isRunningAllTasks = false
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

    taskRunner.lock.withLock {
      assertThat(stalledTasks).isEmpty()
    }
  }

  /** Unblock a waiting task thread. Used by the test thread only. */
  fun interruptCoordinatorThread() {
    taskRunner.assertThreadDoesntHoldLock()

    // Make sure the coordinator is ready to be interrupted.
    runTasks()

    taskRunner.lock.withLock {
      val toInterrupt = waitingCoordinatorThread ?: error("no thread currently waiting")
      taskBecameStalled.drainPermits()
      toInterrupt.interrupt()
    }

    waitForTasksToStall()
  }

  /** Ask a single task to proceed. Used by the test thread only. */
  fun runNextTask() {
    taskRunner.assertThreadDoesntHoldLock()

    taskRunner.lock.withLock {
      check(stalledTasks.size >= 1) { "no tasks to run" }
      stalledTasks.removeFirst()
      taskRunner.condition.signalAll()
    }

    waitForTasksToStall()
  }

  /** Sleep until [durationNanos] elapses. For use by the task threads. */
  fun sleep(durationNanos: Long) {
    taskRunner.assertThreadHoldsLock()

    val waitUntil = nanoTime + durationNanos
    while (nanoTime < waitUntil) {
      stall()
    }
  }

  /**
   * Artificially stall until manually resumed by the test thread with [runTasks]. Use this to
   * simulate races in tasks that doesn't have a deterministic sequence.
   */
  fun yield() {
    stall()
  }

  /**
   * This blocking queue hooks into a fake clock rather than using regular JVM timing for functions
   * like [poll]. It is only usable within task faker tasks.
   */
  private inner class TaskFakerBlockingQueue<T>(
    val delegate: BlockingQueue<T>
  ) : AbstractQueue<T>(), BlockingQueue<T> {
    override val size: Int = delegate.size

    override fun poll(): T = delegate.poll()

    override fun poll(timeout: Long, unit: TimeUnit): T? {
      taskRunner.assertThreadHoldsLock()

      val waitUntil = nanoTime + unit.toNanos(timeout)
      while (true) {
        val result = poll()
        if (result != null) return result
        if (nanoTime >= waitUntil) return null
        stall()
      }
    }

    override fun put(element: T) {
      taskRunner.assertThreadHoldsLock()

      delegate.put(element)
      unstallTasks()
    }

    override fun iterator() = error("unsupported")

    override fun offer(e: T) = error("unsupported")

    override fun peek(): T = error("unsupported")

    override fun offer(element: T, timeout: Long, unit: TimeUnit) = error("unsupported")

    override fun take() = error("unsupported")

    override fun remainingCapacity() = error("unsupported")

    override fun drainTo(sink: MutableCollection<in T>) = error("unsupported")

    override fun drainTo(sink: MutableCollection<in T>, maxElements: Int) = error("unsupported")
  }

  /** Returns true if no tasks have been scheduled. This runs the coordinator for confirmation. */
  fun isIdle() = taskRunner.activeQueues().isEmpty()

  override fun close() {
    tasksExecutor.shutdownNow()
  }

  companion object {
    var instance = 0

    @JvmField
    val assertionsEnabled: Boolean = OkHttpClient::class.java.desiredAssertionStatus()
  }
}
