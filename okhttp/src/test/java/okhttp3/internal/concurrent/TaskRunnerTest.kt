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

import okhttp3.internal.concurrent.TaskRunnerTest.FakeBackend
import okhttp3.internal.notify
import okhttp3.internal.wait
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * This test uses [FakeBackend] so that everything is sequential and deterministic.
 *
 * All tasks are executed synchronously on the test thread. The coordinator does run in a background
 * thread. Its [FakeBackend.coordinatorNotify] and [FakeBackend.coordinatorWait] calls don't use
 * wall-clock time to avoid delays.
 */
class TaskRunnerTest {
  private val backend = FakeBackend()
  private val taskRunner = TaskRunner(backend)
  private val log = mutableListOf<String>()
  private val redQueue = taskRunner.newQueue("red")
  private val blueQueue = taskRunner.newQueue("blue")
  private val greenQueue = taskRunner.newQueue("green")

  @Test fun executeDelayed() {
    redQueue.schedule(object : Task("task") {
      override fun runOnce(): Long {
        log += "run@${backend.nanoTime()}"
        return -1L
      }
    }, 100L)

    backend.advanceUntil(taskRunner, 0L)
    assertThat(log).containsExactly()

    backend.advanceUntil(taskRunner, 99L)
    assertThat(log).containsExactly()

    backend.advanceUntil(taskRunner, 100L)
    assertThat(log).containsExactly("run@100")

    backend.assertNoMoreTasks()
  }

  @Test fun executeRepeated() {
    redQueue.schedule(object : Task("task") {
      val delays = mutableListOf(50L, 150L, -1L)
      override fun runOnce(): Long {
        log += "run@${backend.nanoTime()}"
        return delays.removeAt(0)
      }
    }, 100L)

    backend.advanceUntil(taskRunner, 0L)
    assertThat(log).containsExactly()

    backend.advanceUntil(taskRunner, 100L)
    assertThat(log).containsExactly("run@100")

    backend.advanceUntil(taskRunner, 150L)
    assertThat(log).containsExactly("run@100", "run@150")

    backend.advanceUntil(taskRunner, 299L)
    assertThat(log).containsExactly("run@100", "run@150")

    backend.advanceUntil(taskRunner, 300L)
    assertThat(log).containsExactly("run@100", "run@150", "run@300")

    backend.assertNoMoreTasks()
  }

  /** Repeat with a delay of 200 but schedule with a delay of 50. The schedule wins. */
  @Test fun executeScheduledEarlierReplacesRepeatedLater() {
    redQueue.schedule(object : Task("task") {
      val schedules = mutableListOf(50L)
      val delays = mutableListOf(200L, -1L)
      override fun runOnce(): Long {
        log += "run@${backend.nanoTime()}"
        if (schedules.isNotEmpty()) {
          redQueue.schedule(this, schedules.removeAt(0))
        }
        return delays.removeAt(0)
      }
    }, 100L)

    backend.advanceUntil(taskRunner, 0L)
    assertThat(log).isEmpty()

    backend.advanceUntil(taskRunner, 100L)
    assertThat(log).containsExactly("run@100")

    backend.advanceUntil(taskRunner, 150L)
    assertThat(log).containsExactly("run@100", "run@150")

    backend.assertNoMoreTasks()
  }

  /** Schedule with a delay of 200 but repeat with a delay of 50. The repeat wins. */
  @Test fun executeRepeatedEarlierReplacesScheduledLater() {
    redQueue.schedule(object : Task("task") {
      val schedules = mutableListOf(200L)
      val delays = mutableListOf(50L, -1L)
      override fun runOnce(): Long {
        log += "run@${backend.nanoTime()}"
        if (schedules.isNotEmpty()) {
          redQueue.schedule(this, schedules.removeAt(0))
        }
        return delays.removeAt(0)
      }
    }, 100L)

    backend.advanceUntil(taskRunner, 0L)
    assertThat(log).isEmpty()

    backend.advanceUntil(taskRunner, 100L)
    assertThat(log).containsExactly("run@100")

    backend.advanceUntil(taskRunner, 150L)
    assertThat(log).containsExactly("run@100", "run@150")

    backend.assertNoMoreTasks()
  }

  @Test fun cancelReturnsTruePreventsNextExecution() {
    redQueue.schedule(object : Task("task") {
      override fun runOnce(): Long {
        log += "run@${backend.nanoTime()}"
        return -1L
      }

      override fun tryCancel(): Boolean {
        log += "cancel@${backend.nanoTime()}"
        return true
      }
    }, 100L)

    backend.advanceUntil(taskRunner, 0L)
    assertThat(log).isEmpty()

    redQueue.cancelAll()

    backend.advanceUntil(taskRunner, 99L)
    assertThat(log).containsExactly("cancel@99")

    backend.assertNoMoreTasks()
  }

  @Test fun cancelReturnsFalseDoesNotCancel() {
    redQueue.schedule(object : Task("task") {
      override fun runOnce(): Long {
        log += "run@${backend.nanoTime()}"
        return -1L
      }

      override fun tryCancel(): Boolean {
        log += "cancel@${backend.nanoTime()}"
        return false
      }
    }, 100L)

    backend.advanceUntil(taskRunner, 0L)
    assertThat(log).isEmpty()

    redQueue.cancelAll()

    backend.advanceUntil(taskRunner, 99L)
    assertThat(log).containsExactly("cancel@99")

    backend.advanceUntil(taskRunner, 100L)
    assertThat(log).containsExactly("cancel@99", "run@100")

    backend.assertNoMoreTasks()
  }

  @Test fun cancelWhileExecutingPreventsRepeat() {
    redQueue.schedule(object : Task("task") {
      override fun runOnce(): Long {
        log += "run@${backend.nanoTime()}"
        redQueue.cancelAll()
        return 100L
      }

      override fun tryCancel(): Boolean {
        log += "cancel@${backend.nanoTime()}"
        return true
      }
    }, 100L)

    backend.advanceUntil(taskRunner, 0L)
    assertThat(log).isEmpty()

    backend.advanceUntil(taskRunner, 100L)
    assertThat(log).containsExactly("run@100", "cancel@100")

    backend.assertNoMoreTasks()
  }

  @Test fun cancelWhileExecutingDoesNothingIfTaskDoesNotRepeat() {
    redQueue.schedule(object : Task("task") {
      override fun runOnce(): Long {
        log += "run@${backend.nanoTime()}"
        redQueue.cancelAll()
        return -1L
      }

      override fun tryCancel(): Boolean {
        log += "cancel@${backend.nanoTime()}"
        return true
      }
    }, 100L)

    backend.advanceUntil(taskRunner, 0L)
    assertThat(log).isEmpty()

    backend.advanceUntil(taskRunner, 100L)
    assertThat(log).containsExactly("run@100")

    backend.assertNoMoreTasks()
  }

  @Test fun interruptingCoordinatorAttemptsToCancelsAndSucceeds() {
    redQueue.schedule(object : Task("task") {
      override fun runOnce(): Long {
        log += "run@${backend.nanoTime()}"
        return -1L
      }

      override fun tryCancel(): Boolean {
        log += "cancel@${backend.nanoTime()}"
        return true
      }
    }, 100L)

    backend.advanceUntil(taskRunner, 0L)
    assertThat(log).isEmpty()

    backend.interruptCoordinatorThread(taskRunner)

    backend.advanceUntil(taskRunner, 0L)
    assertThat(log).containsExactly("cancel@0")

    backend.assertNoMoreTasks()
  }

  @Test fun interruptingCoordinatorAttemptsToCancelsAndFails() {
    redQueue.schedule(object : Task("task") {
      override fun runOnce(): Long {
        log += "run@${backend.nanoTime()}"
        return -1L
      }

      override fun tryCancel(): Boolean {
        log += "cancel@${backend.nanoTime()}"
        return false
      }
    }, 100L)

    backend.advanceUntil(taskRunner, 0L)
    assertThat(log).isEmpty()

    backend.interruptCoordinatorThread(taskRunner)

    backend.advanceUntil(taskRunner, 0L)
    assertThat(log).containsExactly("cancel@0")

    backend.advanceUntil(taskRunner, 100L)
    assertThat(log).containsExactly("cancel@0", "run@100")

    backend.assertNoMoreTasks()
  }

  /** Inspect how many runnables have been enqueued. If none then we're truly sequential. */
  @Test fun singleQueueIsSerial() {
    redQueue.schedule(object : Task("task one") {
      override fun runOnce(): Long {
        log += "one:run@${backend.nanoTime()} tasksSize=${backend.tasksSize}"
        return -1L
      }
    }, 100L)

    redQueue.schedule(object : Task("task two") {
      override fun runOnce(): Long {
        log += "two:run@${backend.nanoTime()} tasksSize=${backend.tasksSize}"
        return -1L
      }
    }, 100L)

    redQueue.schedule(object : Task("task three") {
      override fun runOnce(): Long {
        log += "three:run@${backend.nanoTime()} tasksSize=${backend.tasksSize}"
        return -1L
      }
    }, 100L)

    backend.advanceUntil(taskRunner, 0L)
    assertThat(log).isEmpty()

    backend.advanceUntil(taskRunner, 100L)
    assertThat(log).containsExactly(
        "one:run@100 tasksSize=0",
        "two:run@100 tasksSize=0",
        "three:run@100 tasksSize=0"
    )

    backend.assertNoMoreTasks()
  }

  /** Inspect how many runnables have been enqueued. If non-zero then we're truly parallel. */
  @Test fun differentQueuesAreParallel() {
    redQueue.schedule(object : Task("task one") {
      override fun runOnce(): Long {
        log += "one:run@${backend.nanoTime()} tasksSize=${backend.tasksSize}"
        return -1L
      }
    }, 100L)

    blueQueue.schedule(object : Task("task two") {
      override fun runOnce(): Long {
        log += "two:run@${backend.nanoTime()} tasksSize=${backend.tasksSize}"
        return -1L
      }
    }, 100L)

    greenQueue.schedule(object : Task("task three") {
      override fun runOnce(): Long {
        log += "three:run@${backend.nanoTime()} tasksSize=${backend.tasksSize}"
        return -1L
      }
    }, 100L)

    backend.advanceUntil(taskRunner, 0L)
    assertThat(log).isEmpty()

    backend.advanceUntil(taskRunner, 100L)
    assertThat(log).containsExactly(
        "one:run@100 tasksSize=2",
        "two:run@100 tasksSize=1",
        "three:run@100 tasksSize=0"
    )

    backend.assertNoMoreTasks()
  }

  /** Test the introspection method [TaskQueue.scheduledTasks]. */
  @Test fun scheduledTasks() {
    redQueue.schedule(object : Task("task one") {
      override fun runOnce(): Long = -1L

      override fun toString() = "one"
    }, 100L)

    redQueue.schedule(object : Task("task two") {
      override fun runOnce(): Long = -1L

      override fun toString() = "two"
    }, 200L)

    assertThat(redQueue.scheduledTasks.toString()).isEqualTo("[one, two]")
  }

  /**
   * We don't track the active task in scheduled tasks. This behavior might be a mistake, but it's
   * cumbersome to implement properly because the active task might be a cancel.
   */
  @Test fun scheduledTasksDoesNotIncludeRunningTask() {
    redQueue.schedule(object : Task("task one") {
      val schedules = mutableListOf(200L)
      override fun runOnce(): Long {
        if (schedules.isNotEmpty()) {
          redQueue.schedule(this, schedules.removeAt(0)) // Add it at the end also.
        }
        log += "scheduledTasks=${redQueue.scheduledTasks}"
        return -1L
      }

      override fun toString() = "one"
    }, 100L)

    redQueue.schedule(object : Task("task two") {
      override fun runOnce(): Long {
        log += "scheduledTasks=${redQueue.scheduledTasks}"
        return -1L
      }

      override fun toString() = "two"
    }, 200L)

    backend.advanceUntil(taskRunner, 100L)
    assertThat(log).containsExactly(
        "scheduledTasks=[two, one]"
    )

    backend.advanceUntil(taskRunner, 200L)
    assertThat(log).containsExactly(
        "scheduledTasks=[two, one]",
        "scheduledTasks=[one]"
    )

    backend.advanceUntil(taskRunner, 300L)
    assertThat(log).containsExactly(
        "scheduledTasks=[two, one]",
        "scheduledTasks=[one]",
        "scheduledTasks=[]"
    )

    backend.assertNoMoreTasks()
  }

  /**
   * The runner doesn't hold references to its queues! Otherwise we'd need a mechanism to clean them
   * up when they're no longer needed and that's annoying. Instead the task runner only tracks which
   * queues have work scheduled.
   */
  @Test fun activeQueuesContainsOnlyQueuesWithScheduledTasks() {
    redQueue.schedule(object : Task("task one") {
      override fun runOnce() = -1L
    }, 100L)

    blueQueue.schedule(object : Task("task two") {
      override fun runOnce() = -1L
    }, 200L)

    backend.advanceUntil(taskRunner, 0L)
    assertThat(taskRunner.activeQueues()).containsExactly(redQueue, blueQueue)

    backend.advanceUntil(taskRunner, 100L)
    assertThat(taskRunner.activeQueues()).containsExactly(blueQueue)

    backend.advanceUntil(taskRunner, 200L)
    assertThat(taskRunner.activeQueues()).isEmpty()

    backend.assertNoMoreTasks()
  }

  @Test fun taskNameIsUsedForThreadNameWhenRunning() {
    redQueue.schedule(object : Task("lucky task") {
      override fun runOnce(): Long {
        log += "run threadName:${Thread.currentThread().name}"
        return -1L
      }
    })

    backend.advanceUntil(taskRunner, 0L)
    assertThat(log).containsExactly("run threadName:lucky task")

    backend.assertNoMoreTasks()
  }

  @Test fun taskNameIsUsedForThreadNameWhenCanceling() {
    redQueue.schedule(object : Task("lucky task") {
      override fun tryCancel(): Boolean {
        log += "cancel threadName:${Thread.currentThread().name}"
        return true
      }

      override fun runOnce() = -1L
    }, 100L)

    backend.advanceUntil(taskRunner, 0L)
    assertThat(log).isEmpty()

    redQueue.cancelAll()

    backend.advanceUntil(taskRunner, 0L)
    assertThat(log).containsExactly("cancel threadName:lucky task")

    backend.assertNoMoreTasks()
  }

  class FakeBackend : TaskRunner.Backend {
    /** Null unless there's a coordinator runnable that needs to be started. */
    private var coordinatorToRun: Runnable? = null

    /** Null unless there's a coordinator thread currently executing. */
    var coordinatorThread: Thread? = null

    /** Tasks to be executed by the test thread. */
    private val tasks = mutableListOf<Runnable>()

    /** How many tasks can be executed immediately. */
    val tasksSize: Int get() = tasks.size

    /** Guarded by taskRunner. */
    private var nanoTime = 0L

    /** Guarded by taskRunner. Time at which we should yield execution to the coordinator. */
    private var coordinatorWaitingUntilTime = Long.MAX_VALUE

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

    /** Advance the simulated clock and run anything ready at the new time. */
    fun advanceUntil(taskRunner: TaskRunner, newTime: Long) {
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

      while (tasks.isNotEmpty()) {
        val task = tasks.removeAt(0)
        task.run()
      }
    }

    fun assertNoMoreTasks() {
      assertThat(coordinatorToRun).isNull()
      assertThat(tasks).isEmpty()
      assertThat(coordinatorWaitingUntilTime).isEqualTo(Long.MAX_VALUE)
    }

    fun interruptCoordinatorThread(taskRunner: TaskRunner) {
      check(!Thread.holdsLock(taskRunner))

      synchronized(taskRunner) {
        coordinatorThread!!.interrupt()
        taskRunner.wait() // Wait for the coordinator to stall.
      }
    }
  }
}
