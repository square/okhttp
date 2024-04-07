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

import assertk.assertThat
import assertk.assertions.isEmpty
import java.io.Closeable
import java.util.AbstractQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kotlin.concurrent.withLock
import okhttp3.OkHttpClient

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
  private val tasksExecutor = Executors.newCachedThreadPool(object : ThreadFactory {
    private var nextId = 1
    override fun newThread(runnable: Runnable) = Thread(runnable, "TaskFaker-${nextId++}")
  })

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

  /** Tasks to run in sequence. Guarded by [taskRunner]. */
  private val serialTaskQueue = ArrayDeque<SerialTask>()

  /** The task that's currently executing, or null if idle. Guarded by [taskRunner]. */
  private var currentTask: SerialTask? = null

  /** How many times a new task has been started. Guarded by [taskRunner]. */
  private var contextSwitchCount = 0

  /** A task runner that posts tasks to this fake. Tasks won't be executed until requested. */
  val taskRunner: TaskRunner =
    TaskRunner(
      object : TaskRunner.Backend {
        override fun execute(
          taskRunner: TaskRunner,
          runnable: Runnable,
        ) {
          taskRunner.assertThreadHoldsLock()

          val queuedTask = RunnableSerialTask(runnable)
          serialTaskQueue += queuedTask
          isParallel = serialTaskQueue.size > 1
        }

        override fun nanoTime() = nanoTime

        override fun coordinatorNotify(taskRunner: TaskRunner) {
          taskRunner.assertThreadHoldsLock()
          check(waitingCoordinatorThread != null)

          waitingCoordinatorThread = null
          taskRunner.condition.signalAll()
        }

        override fun coordinatorWait(
          taskRunner: TaskRunner,
          nanos: Long,
        ) {
          taskRunner.assertThreadHoldsLock()

          check(waitingCoordinatorThread == null)
          if (nanos == 0L) return

          val waitUntil = nanoTime + nanos
          val currentThread = Thread.currentThread()
          waitingCoordinatorThread = currentThread
          yieldUntil { waitingCoordinatorThread != currentThread || nanoTime >= waitUntil }
          if (waitingCoordinatorThread == currentThread) {
            waitingCoordinatorThread = null
          }
        }

        override fun <T> decorate(queue: BlockingQueue<T>) = TaskFakerBlockingQueue(queue)
      },
      logger = logger,
    )

  /** Runs all tasks that are ready. Used by the test thread only. */
  fun runTasks() {
    advanceUntil(nanoTime)
  }

  /** Advance the simulated clock, then runs tasks that are ready. Used by the test thread only. */
  fun advanceUntil(newTime: Long) {
    taskRunner.assertThreadDoesntHoldLock()

    taskRunner.lock.withLock {
      check(currentTask == null)
      nanoTime = newTime
      currentTask = TestThreadSerialTask
      try {
        yieldUntil(untilExhausted = true)
      } finally {
        currentTask = null
      }
    }
  }

  /** Confirm all tasks have completed. Used by the test thread only. */
  fun assertNoMoreTasks() {
    taskRunner.assertThreadDoesntHoldLock()

    taskRunner.lock.withLock {
      assertThat(serialTaskQueue).isEmpty()
    }
  }

  /** Unblock a waiting task thread. Used by the test thread only. */
  fun interruptCoordinatorThread() {
    taskRunner.assertThreadDoesntHoldLock()
    require(currentTask == null)

    // Make sure the coordinator is ready to be interrupted.
    runTasks()

    taskRunner.lock.withLock {
      val toInterrupt = waitingCoordinatorThread ?: error("no thread currently waiting")
      toInterrupt.interrupt()
    }

    // Let the coordinator process its interruption.
    runTasks()
  }

  /** Ask a single task to proceed. Used by the test thread only. */
  fun runNextTask() {
    taskRunner.assertThreadDoesntHoldLock()

    taskRunner.lock.withLock {
      val contextSwitchCountBefore = contextSwitchCount
      yieldUntil(resumeEagerly = true) { contextSwitchCount > contextSwitchCountBefore }
    }
  }

  /** Sleep until [durationNanos] elapses. For use by the task threads. */
  fun sleep(durationNanos: Long) {
    taskRunner.assertThreadHoldsLock()
    val sleepUntil = nanoTime + durationNanos
    yieldUntil { nanoTime >= sleepUntil }
  }

  /**
   * Artificially stall until manually resumed by the test thread with [runTasks]. Use this to
   * simulate races in tasks that doesn't have a deterministic sequence.
   */
  fun yield() {
    taskRunner.assertThreadDoesntHoldLock()
    taskRunner.lock.withLock {
      yieldUntil()
    }
  }

  /**
   * Process the queue until [untilConditionIsMet] returns true.
   *
   * @param resumeEagerly true to prioritize the current task over other queued tasks.
   * @param untilExhausted true to keep yielding until there's no other runnable tasks.
   */
  private tailrec fun yieldUntil(
    resumeEagerly: Boolean = false,
    untilExhausted: Boolean = false,
    untilConditionIsMet: () -> Boolean = { true },
  ) {
    taskRunner.assertThreadHoldsLock()
    val self = currentTask ?: error("no executing queue entry?")

    val yieldCompleteTask = object : SerialTask {
      override fun isReady() = untilConditionIsMet()

      override fun start() {
        currentTask = self
        taskRunner.condition.signalAll()
      }
    }

    if (resumeEagerly) {
      serialTaskQueue.addFirst(yieldCompleteTask)
    } else {
      serialTaskQueue.addLast(yieldCompleteTask)
    }

    currentTask = null

    val startedTask = startNextTask()
    val otherTasksStarted = startedTask != yieldCompleteTask

    while (currentTask != self) {
      taskRunner.condition.await()
    }

    if (untilExhausted && otherTasksStarted) {
      return yieldUntil(resumeEagerly, true, untilConditionIsMet)
    }
  }

  /** Returns the task that was started, or null if there were no tasks to start. */
  private fun startNextTask(): SerialTask? {
    taskRunner.assertThreadHoldsLock()
    require(currentTask == null)

    val index = serialTaskQueue.indexOfFirst { it.isReady() }
    if (index == -1) return null

    val nextTask = serialTaskQueue.removeAt(index)
    currentTask = nextTask
    contextSwitchCount++
    nextTask.start()
    return nextTask
  }

  private interface SerialTask {
    fun isReady() = true

    /** Do this task's work, then start another, such as by calling [startNextTask]. */
    fun start()
  }

  private object TestThreadSerialTask : SerialTask {
    override fun start() = error("unexpected call")
  }

  inner class RunnableSerialTask(
    private val runnable: Runnable,
  ) : SerialTask {
    @Volatile
    var started = false
      private set

    override fun start() {
      require(currentTask == this)
      started = true
      tasksExecutor.execute {
        taskRunner.assertThreadDoesntHoldLock()
        require(currentTask == this)
        try {
          runnable.run()
        } catch (e: InterruptedException) {
          if (!tasksExecutor.isShutdown) throw e // Ignore shutdown-triggered interruptions.
        } finally {
          taskRunner.lock.withLock {
            currentTask = null
            startNextTask()
          }
        }
      }
    }
  }

  /**
   * This blocking queue hooks into a fake clock rather than using regular JVM timing for functions
   * like [poll]. It is only usable within task faker tasks.
   */
  private inner class TaskFakerBlockingQueue<T>(
    val delegate: BlockingQueue<T>,
  ) : AbstractQueue<T>(), BlockingQueue<T> {
    override val size: Int = delegate.size

    private var editCount = 0

    override fun poll(): T = delegate.poll()

    override fun poll(
      timeout: Long,
      unit: TimeUnit,
    ): T? {
      taskRunner.assertThreadHoldsLock()

      val waitUntil = nanoTime + unit.toNanos(timeout)
      while (true) {
        val result = poll()
        if (result != null) return result
        if (nanoTime >= waitUntil) return null
        val editCountBefore = editCount
        yieldUntil { editCount > editCountBefore }
      }
    }

    override fun put(element: T) {
      taskRunner.assertThreadHoldsLock()

      delegate.put(element)

      editCount++
      taskRunner.condition.signalAll()
    }

    override fun iterator() = error("unsupported")

    override fun offer(e: T) = error("unsupported")

    override fun peek(): T = error("unsupported")

    override fun offer(
      element: T,
      timeout: Long,
      unit: TimeUnit,
    ) = error("unsupported")

    override fun take() = error("unsupported")

    override fun remainingCapacity() = error("unsupported")

    override fun drainTo(sink: MutableCollection<in T>) = error("unsupported")

    override fun drainTo(
      sink: MutableCollection<in T>,
      maxElements: Int,
    ) = error("unsupported")
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
