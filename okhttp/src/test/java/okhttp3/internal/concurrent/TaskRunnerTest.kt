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

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class TaskRunnerTest {
  private val taskFaker = TaskFaker()
  private val taskRunner = taskFaker.taskRunner
  private val log = mutableListOf<String>()
  private val redQueue = taskRunner.newQueue("red")
  private val blueQueue = taskRunner.newQueue("blue")
  private val greenQueue = taskRunner.newQueue("green")

  @Test fun executeDelayed() {
    redQueue.schedule(object : Task("task") {
      override fun runOnce(): Long {
        log += "run@${taskFaker.nanoTime}"
        return -1L
      }
    }, 100L)

    taskFaker.advanceUntil(0L)
    assertThat(log).containsExactly()

    taskFaker.advanceUntil(99L)
    assertThat(log).containsExactly()

    taskFaker.advanceUntil(100L)
    assertThat(log).containsExactly("run@100")

    taskFaker.assertNoMoreTasks()
  }

  @Test fun executeRepeated() {
    redQueue.schedule(object : Task("task") {
      val delays = mutableListOf(50L, 150L, -1L)
      override fun runOnce(): Long {
        log += "run@${taskFaker.nanoTime}"
        return delays.removeAt(0)
      }
    }, 100L)

    taskFaker.advanceUntil(0L)
    assertThat(log).containsExactly()

    taskFaker.advanceUntil(100L)
    assertThat(log).containsExactly("run@100")

    taskFaker.advanceUntil(150L)
    assertThat(log).containsExactly("run@100", "run@150")

    taskFaker.advanceUntil(299L)
    assertThat(log).containsExactly("run@100", "run@150")

    taskFaker.advanceUntil(300L)
    assertThat(log).containsExactly("run@100", "run@150", "run@300")

    taskFaker.assertNoMoreTasks()
  }

  /** Repeat with a delay of 200 but schedule with a delay of 50. The schedule wins. */
  @Test fun executeScheduledEarlierReplacesRepeatedLater() {
    redQueue.schedule(object : Task("task") {
      val schedules = mutableListOf(50L)
      val delays = mutableListOf(200L, -1L)
      override fun runOnce(): Long {
        log += "run@${taskFaker.nanoTime}"
        if (schedules.isNotEmpty()) {
          redQueue.schedule(this, schedules.removeAt(0))
        }
        return delays.removeAt(0)
      }
    }, 100L)

    taskFaker.advanceUntil(0L)
    assertThat(log).isEmpty()

    taskFaker.advanceUntil(100L)
    assertThat(log).containsExactly("run@100")

    taskFaker.advanceUntil(150L)
    assertThat(log).containsExactly("run@100", "run@150")

    taskFaker.assertNoMoreTasks()
  }

  /** Schedule with a delay of 200 but repeat with a delay of 50. The repeat wins. */
  @Test fun executeRepeatedEarlierReplacesScheduledLater() {
    redQueue.schedule(object : Task("task") {
      val schedules = mutableListOf(200L)
      val delays = mutableListOf(50L, -1L)
      override fun runOnce(): Long {
        log += "run@${taskFaker.nanoTime}"
        if (schedules.isNotEmpty()) {
          redQueue.schedule(this, schedules.removeAt(0))
        }
        return delays.removeAt(0)
      }
    }, 100L)

    taskFaker.advanceUntil(0L)
    assertThat(log).isEmpty()

    taskFaker.advanceUntil(100L)
    assertThat(log).containsExactly("run@100")

    taskFaker.advanceUntil(150L)
    assertThat(log).containsExactly("run@100", "run@150")

    taskFaker.assertNoMoreTasks()
  }

  @Test fun cancelReturnsTruePreventsNextExecution() {
    redQueue.schedule(object : Task("task") {
      override fun runOnce(): Long {
        log += "run@${taskFaker.nanoTime}"
        return -1L
      }

      override fun tryCancel(): Boolean {
        log += "cancel@${taskFaker.nanoTime}"
        return true
      }
    }, 100L)

    taskFaker.advanceUntil(0L)
    assertThat(log).isEmpty()

    redQueue.cancelAll()

    taskFaker.advanceUntil(99L)
    assertThat(log).containsExactly("cancel@99")

    taskFaker.assertNoMoreTasks()
  }

  @Test fun cancelReturnsFalseDoesNotCancel() {
    redQueue.schedule(object : Task("task") {
      override fun runOnce(): Long {
        log += "run@${taskFaker.nanoTime}"
        return -1L
      }

      override fun tryCancel(): Boolean {
        log += "cancel@${taskFaker.nanoTime}"
        return false
      }
    }, 100L)

    taskFaker.advanceUntil(0L)
    assertThat(log).isEmpty()

    redQueue.cancelAll()

    taskFaker.advanceUntil(99L)
    assertThat(log).containsExactly("cancel@99")

    taskFaker.advanceUntil(100L)
    assertThat(log).containsExactly("cancel@99", "run@100")

    taskFaker.assertNoMoreTasks()
  }

  @Test fun cancelWhileExecutingPreventsRepeat() {
    redQueue.schedule(object : Task("task") {
      override fun runOnce(): Long {
        log += "run@${taskFaker.nanoTime}"
        redQueue.cancelAll()
        return 100L
      }

      override fun tryCancel(): Boolean {
        log += "cancel@${taskFaker.nanoTime}"
        return true
      }
    }, 100L)

    taskFaker.advanceUntil(0L)
    assertThat(log).isEmpty()

    taskFaker.advanceUntil(100L)
    assertThat(log).containsExactly("run@100", "cancel@100")

    taskFaker.assertNoMoreTasks()
  }

  @Test fun cancelWhileExecutingDoesNothingIfTaskDoesNotRepeat() {
    redQueue.schedule(object : Task("task") {
      override fun runOnce(): Long {
        log += "run@${taskFaker.nanoTime}"
        redQueue.cancelAll()
        return -1L
      }

      override fun tryCancel(): Boolean {
        log += "cancel@${taskFaker.nanoTime}"
        return true
      }
    }, 100L)

    taskFaker.advanceUntil(0L)
    assertThat(log).isEmpty()

    taskFaker.advanceUntil(100L)
    assertThat(log).containsExactly("run@100")

    taskFaker.assertNoMoreTasks()
  }

  @Test fun interruptingCoordinatorAttemptsToCancelsAndSucceeds() {
    redQueue.schedule(object : Task("task") {
      override fun runOnce(): Long {
        log += "run@${taskFaker.nanoTime}"
        return -1L
      }

      override fun tryCancel(): Boolean {
        log += "cancel@${taskFaker.nanoTime}"
        return true
      }
    }, 100L)

    taskFaker.advanceUntil(0L)
    assertThat(log).isEmpty()

    taskFaker.interruptCoordinatorThread()

    taskFaker.advanceUntil(0L)
    assertThat(log).containsExactly("cancel@0")

    taskFaker.assertNoMoreTasks()
  }

  @Test fun interruptingCoordinatorAttemptsToCancelsAndFails() {
    redQueue.schedule(object : Task("task") {
      override fun runOnce(): Long {
        log += "run@${taskFaker.nanoTime}"
        return -1L
      }

      override fun tryCancel(): Boolean {
        log += "cancel@${taskFaker.nanoTime}"
        return false
      }
    }, 100L)

    taskFaker.advanceUntil(0L)
    assertThat(log).isEmpty()

    taskFaker.interruptCoordinatorThread()

    taskFaker.advanceUntil(0L)
    assertThat(log).containsExactly("cancel@0")

    taskFaker.advanceUntil(100L)
    assertThat(log).containsExactly("cancel@0", "run@100")

    taskFaker.assertNoMoreTasks()
  }

  /** Inspect how many runnables have been enqueued. If none then we're truly sequential. */
  @Test fun singleQueueIsSerial() {
    redQueue.schedule(object : Task("task one") {
      override fun runOnce(): Long {
        log += "one:run@${taskFaker.nanoTime} tasksSize=${taskFaker.tasksSize}"
        return -1L
      }
    }, 100L)

    redQueue.schedule(object : Task("task two") {
      override fun runOnce(): Long {
        log += "two:run@${taskFaker.nanoTime} tasksSize=${taskFaker.tasksSize}"
        return -1L
      }
    }, 100L)

    redQueue.schedule(object : Task("task three") {
      override fun runOnce(): Long {
        log += "three:run@${taskFaker.nanoTime} tasksSize=${taskFaker.tasksSize}"
        return -1L
      }
    }, 100L)

    taskFaker.advanceUntil(0L)
    assertThat(log).isEmpty()

    taskFaker.advanceUntil(100L)
    assertThat(log).containsExactly(
        "one:run@100 tasksSize=0",
        "two:run@100 tasksSize=0",
        "three:run@100 tasksSize=0"
    )

    taskFaker.assertNoMoreTasks()
  }

  /** Inspect how many runnables have been enqueued. If non-zero then we're truly parallel. */
  @Test fun differentQueuesAreParallel() {
    redQueue.schedule(object : Task("task one") {
      override fun runOnce(): Long {
        log += "one:run@${taskFaker.nanoTime} tasksSize=${taskFaker.tasksSize}"
        return -1L
      }
    }, 100L)

    blueQueue.schedule(object : Task("task two") {
      override fun runOnce(): Long {
        log += "two:run@${taskFaker.nanoTime} tasksSize=${taskFaker.tasksSize}"
        return -1L
      }
    }, 100L)

    greenQueue.schedule(object : Task("task three") {
      override fun runOnce(): Long {
        log += "three:run@${taskFaker.nanoTime} tasksSize=${taskFaker.tasksSize}"
        return -1L
      }
    }, 100L)

    taskFaker.advanceUntil(0L)
    assertThat(log).isEmpty()

    taskFaker.advanceUntil(100L)
    assertThat(log).containsExactly(
        "one:run@100 tasksSize=2",
        "two:run@100 tasksSize=1",
        "three:run@100 tasksSize=0"
    )

    taskFaker.assertNoMoreTasks()
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

    taskFaker.advanceUntil(100L)
    assertThat(log).containsExactly(
        "scheduledTasks=[two, one]"
    )

    taskFaker.advanceUntil(200L)
    assertThat(log).containsExactly(
        "scheduledTasks=[two, one]",
        "scheduledTasks=[one]"
    )

    taskFaker.advanceUntil(300L)
    assertThat(log).containsExactly(
        "scheduledTasks=[two, one]",
        "scheduledTasks=[one]",
        "scheduledTasks=[]"
    )

    taskFaker.assertNoMoreTasks()
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

    taskFaker.advanceUntil(0L)
    assertThat(taskRunner.activeQueues()).containsExactly(redQueue, blueQueue)

    taskFaker.advanceUntil(100L)
    assertThat(taskRunner.activeQueues()).containsExactly(blueQueue)

    taskFaker.advanceUntil(200L)
    assertThat(taskRunner.activeQueues()).isEmpty()

    taskFaker.assertNoMoreTasks()
  }

  @Test fun taskNameIsUsedForThreadNameWhenRunning() {
    redQueue.schedule(object : Task("lucky task") {
      override fun runOnce(): Long {
        log += "run threadName:${Thread.currentThread().name}"
        return -1L
      }
    })

    taskFaker.advanceUntil(0L)
    assertThat(log).containsExactly("run threadName:lucky task")

    taskFaker.assertNoMoreTasks()
  }

  @Test fun taskNameIsUsedForThreadNameWhenCanceling() {
    redQueue.schedule(object : Task("lucky task") {
      override fun tryCancel(): Boolean {
        log += "cancel threadName:${Thread.currentThread().name}"
        return true
      }

      override fun runOnce() = -1L
    }, 100L)

    taskFaker.advanceUntil(0L)
    assertThat(log).isEmpty()

    redQueue.cancelAll()

    taskFaker.advanceUntil(0L)
    assertThat(log).containsExactly("cancel threadName:lucky task")

    taskFaker.assertNoMoreTasks()
  }
}
