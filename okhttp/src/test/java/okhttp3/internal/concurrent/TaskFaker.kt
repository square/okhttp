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

import okhttp3.internal.notify
import okhttp3.internal.wait
import org.assertj.core.api.Assertions.assertThat
import java.util.concurrent.Executors

/**
 * Runs a [TaskRunner] in a controlled environment so that everything is sequential and
 * deterministic. All tasks are executed on-demand on the test thread by calls to [runTasks] and
 * [advanceUntil].
 */
class TaskFaker {
  /** Runnables scheduled for execution. These will execute tasks and perform scheduling. */
  private val futureRunnables = mutableListOf<Runnable>()

  /** Runnables currently executing. */
  private val currentRunnables = mutableListOf<Runnable>()

  /**
   * Executor service for the runnables above. This executor service should never have more than two
   * active threads: one for a currently-executing task and one for a currently-sleeping task.
   */
  private val executorService = Executors.newCachedThreadPool()

  /** True if this task faker has ever had multiple tasks scheduled to run concurrently. */
  var isParallel = false

  /** Guarded by [taskRunner]. */
  var nanoTime = 0L
    private set

  /** The thread currently waiting for time to advance. */
  private var waitingThread: Thread? = null

  /** Guarded by taskRunner. Time at which we should yield execution to a waiting runnable. */
  private var waitingUntilTime = Long.MAX_VALUE

  /** Total number of runnables executed. */
  private var executedRunnableCount = 0

  /** Stall once we've executed this many runnables. */
  private var executedTaskLimit = Int.MAX_VALUE

  /** A task runner that posts tasks to this fake. Tasks won't be executed until requested. */
  val taskRunner: TaskRunner = TaskRunner(object : TaskRunner.Backend {
    override fun beforeTask(taskRunner: TaskRunner) {
      check(Thread.holdsLock(taskRunner))
      while (executedRunnableCount >= executedTaskLimit) {
        coordinatorWait(taskRunner, Long.MAX_VALUE)
      }
    }

    override fun execute(runnable: Runnable) {
      futureRunnables.add(runnable)
    }

    override fun nanoTime() = nanoTime

    override fun coordinatorNotify(taskRunner: TaskRunner) {
      check(Thread.holdsLock(taskRunner))
      waitingUntilTime = nanoTime
    }

    override fun coordinatorWait(taskRunner: TaskRunner, nanos: Long) {
      check(Thread.holdsLock(taskRunner))
      check(waitingUntilTime == Long.MAX_VALUE)
      check(waitingThread == null)

      waitingThread = Thread.currentThread()
      waitingUntilTime = if (nanos < Long.MAX_VALUE) nanoTime + nanos else Long.MAX_VALUE
      try {
        if (nanoTime < waitingUntilTime) {
          // Stall because there's no work to do.
          taskRunner.notify()
          taskRunner.wait()
        }
      } finally {
        waitingThread = null
        waitingUntilTime = Long.MAX_VALUE
      }
    }
  })

  /** Runs all tasks that are ready without advancing the simulated clock. */
  fun runTasks() {
    advanceUntil(nanoTime)
  }

  /** Advance the simulated clock and run anything ready at the new time. */
  fun advanceUntil(newTime: Long) {
    check(!Thread.holdsLock(taskRunner))

    synchronized(taskRunner) {
      nanoTime = newTime

      while (true) {
        runRunnables(taskRunner)

        if (waitingUntilTime <= nanoTime) {
          // Let the coordinator do its business at the new time.
          taskRunner.notify()
          taskRunner.wait()
        } else {
          return
        }
      }
    }
  }

  /** Returns true if anything was executed. */
  private fun runRunnables(taskRunner: TaskRunner) {
    check(Thread.holdsLock(taskRunner))

    while (futureRunnables.isNotEmpty()) {
      val runnable = futureRunnables.removeAt(0)
      currentRunnables.add(runnable)
      if (currentRunnables.size > 1) isParallel = true
      executorService.execute(Runnable {
        try {
          runnable.run()
        } finally {
          currentRunnables.remove(runnable)
          synchronized(taskRunner) {
            taskRunner.notify()
          }
        }
      })
      taskRunner.wait() // Wait for the coordinator to stall.
    }
  }

  fun assertNoMoreTasks() {
    assertThat(futureRunnables).isEmpty()
    assertThat(waitingUntilTime)
        .withFailMessage("tasks are scheduled to run at $waitingUntilTime")
        .isEqualTo(Long.MAX_VALUE)
  }

  fun interruptCoordinatorThread() {
    check(!Thread.holdsLock(taskRunner))

    synchronized(taskRunner) {
      check(waitingThread != null) { "no thread currently waiting" }
      waitingThread!!.interrupt()
      taskRunner.wait() // Wait for the coordinator to stall.
    }
  }

  /** Advances and runs up to one task. */
  fun runNextTask() {
    executedTaskLimit = executedRunnableCount + 1
    try {
      advanceUntil(nanoTime)
    } finally {
      executedTaskLimit = Int.MAX_VALUE
    }
  }

  /** Returns true if no tasks have been scheduled. This runs the coordinator for confirmation. */
  fun isIdle() = taskRunner.activeQueues().isEmpty()
}
