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

/**
 * Runs a [TaskRunner] in a controlled environment so that everything is sequential and
 * deterministic. All tasks are executed on-demand on the test thread by calls to [runTasks] and
 * [advanceUntil].
 *
 * The coordinator does run in a background thread. Its [TaskRunner.Backend.coordinatorNotify] and
 * [TaskRunner.Backend.coordinatorWait] calls don't use wall-clock time to avoid delays.
 */
class TaskFaker {
  /** Null unless there's a coordinator runnable that needs to be started. */
  private var coordinatorToRun: Runnable? = null

  /** Null unless there's a coordinator thread currently executing. */
  private var coordinatorThread: Thread? = null

  /** Tasks to be executed by the test thread. */
  private val tasks = mutableListOf<Runnable>()

  /** How many tasks can be executed immediately. */
  val tasksSize: Int get() = tasks.size

  /** Guarded by taskRunner. */
  var nanoTime = 0L
    private set

  /** Guarded by taskRunner. Time at which we should yield execution to the coordinator. */
  private var coordinatorWaitingUntilTime = Long.MAX_VALUE

  /** Total number of tasks executed. */
  private var executedTaskCount = 0

  /** Stall once we've executed this many tasks. */
  private var executedTaskLimit = Int.MAX_VALUE

  /** A task runner that posts tasks to this fake. Tasks won't be executed until requested. */
  val taskRunner: TaskRunner = TaskRunner(object : TaskRunner.Backend {
    override fun executeCoordinator(runnable: Runnable) {
      check(coordinatorToRun == null)
      coordinatorToRun = runnable
    }

    override fun executeTask(runnable: Runnable) {
      tasks += runnable
    }

    override fun nanoTime(): Long {
      return nanoTime
    }

    override fun coordinatorNotify(taskRunner: TaskRunner) {
      check(Thread.holdsLock(taskRunner))
      coordinatorWaitingUntilTime = nanoTime
    }

    override fun coordinatorWait(taskRunner: TaskRunner, nanos: Long) {
      check(Thread.holdsLock(taskRunner))

      coordinatorWaitingUntilTime = if (nanos < Long.MAX_VALUE) nanoTime + nanos else Long.MAX_VALUE
      if (nanoTime < coordinatorWaitingUntilTime) {
        // Stall because there's no work to do.
        taskRunner.notify()
        taskRunner.wait()
      }
      coordinatorWaitingUntilTime = Long.MAX_VALUE
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

        if (coordinatorWaitingUntilTime <= nanoTime) {
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

    if (coordinatorToRun != null) {
      coordinatorThread = object : Thread() {
        val runnable = coordinatorToRun!!
        override fun run() {
          runnable.run()
          synchronized(taskRunner) {
            coordinatorThread = null
            coordinatorWaitingUntilTime = Long.MAX_VALUE
            taskRunner.notify() // Release the waiting advanceUntil() or runRunnables() call.
          }
        }
      }
      coordinatorThread!!.start()
      coordinatorToRun = null
      taskRunner.wait() // Wait for the coordinator to stall.
    }

    while (tasks.isNotEmpty() && executedTaskCount < executedTaskLimit) {
      val task = tasks.removeAt(0)
      task.run()
      executedTaskCount++
    }
  }

  fun assertNoMoreTasks() {
    assertThat(coordinatorToRun).isNull()
    assertThat(tasks).isEmpty()
    assertThat(coordinatorWaitingUntilTime).isEqualTo(Long.MAX_VALUE)
  }

  fun interruptCoordinatorThread() {
    check(!Thread.holdsLock(taskRunner))

    synchronized(taskRunner) {
      coordinatorThread!!.interrupt()
      taskRunner.wait() // Wait for the coordinator to stall.
    }
  }

  /** Advances and runs up to one task. */
  fun runNextTask() {
    executedTaskLimit = executedTaskCount + 1
    try {
      advanceUntil(nanoTime)
    } finally {
      executedTaskLimit = Int.MAX_VALUE
    }
  }

  /** Returns true if no tasks have been scheduled. This runs the coordinator for confirmation. */
  fun isIdle() = taskRunner.activeQueues().isEmpty()
}
