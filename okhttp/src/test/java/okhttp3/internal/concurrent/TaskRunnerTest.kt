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
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.RejectedExecutionException

class TaskRunnerTest {
  private val taskFaker = TaskFaker()
  private val taskRunner = taskFaker.taskRunner
  private val log = mutableListOf<String>()
  private val redQueue = taskRunner.newQueue()
  private val blueQueue = taskRunner.newQueue()
  private val greenQueue = taskRunner.newQueue()

  @Test fun executeDelayed() {
    redQueue.execute("task", 100L) {
      log += "run@${taskFaker.nanoTime}"
    }

    taskFaker.advanceUntil(0L)
    assertThat(log).containsExactly()

    taskFaker.advanceUntil(99L)
    assertThat(log).containsExactly()

    taskFaker.advanceUntil(100L)
    assertThat(log).containsExactly("run@100")

    taskFaker.assertNoMoreTasks()
  }

  @Test fun executeRepeated() {
    val delays = mutableListOf(50L, 150L, -1L)
    redQueue.schedule("task", 100L) {
      log += "run@${taskFaker.nanoTime}"
      return@schedule delays.removeAt(0)
    }

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
    val task = object : Task("task") {
      val schedules = mutableListOf(50L)
      val delays = mutableListOf(200L, -1L)
      override fun runOnce(): Long {
        log += "run@${taskFaker.nanoTime}"
        if (schedules.isNotEmpty()) {
          redQueue.schedule(this, schedules.removeAt(0))
        }
        return delays.removeAt(0)
      }
    }
    redQueue.schedule(task, 100L)

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
    val task = object : Task("task") {
      val schedules = mutableListOf(200L)
      val delays = mutableListOf(50L, -1L)
      override fun runOnce(): Long {
        log += "run@${taskFaker.nanoTime}"
        if (schedules.isNotEmpty()) {
          redQueue.schedule(this, schedules.removeAt(0))
        }
        return delays.removeAt(0)
      }
    }
    redQueue.schedule(task, 100L)

    taskFaker.advanceUntil(0L)
    assertThat(log).isEmpty()

    taskFaker.advanceUntil(100L)
    assertThat(log).containsExactly("run@100")

    taskFaker.advanceUntil(150L)
    assertThat(log).containsExactly("run@100", "run@150")

    taskFaker.assertNoMoreTasks()
  }

  @Test fun cancelReturnsTruePreventsNextExecution() {
    redQueue.execute("task", 100L) {
      log += "run@${taskFaker.nanoTime}"
    }

    taskFaker.advanceUntil(0L)
    assertThat(log).isEmpty()

    redQueue.cancelAll()

    taskFaker.advanceUntil(99L)
    assertThat(log).isEmpty()

    taskFaker.assertNoMoreTasks()
  }

  @Test fun cancelReturnsFalseDoesNotCancel() {
    redQueue.schedule(object : Task("task", cancelable = false) {
      override fun runOnce(): Long {
        log += "run@${taskFaker.nanoTime}"
        return -1L
      }
    }, 100L)

    taskFaker.advanceUntil(0L)
    assertThat(log).isEmpty()

    redQueue.cancelAll()

    taskFaker.advanceUntil(99L)
    assertThat(log).isEmpty()

    taskFaker.advanceUntil(100L)
    assertThat(log).containsExactly("run@100")

    taskFaker.assertNoMoreTasks()
  }

  @Test fun cancelWhileExecutingPreventsRepeat() {
    redQueue.schedule("task", 100L) {
      log += "run@${taskFaker.nanoTime}"
      redQueue.cancelAll()
      return@schedule 100L
    }

    taskFaker.advanceUntil(0L)
    assertThat(log).isEmpty()

    taskFaker.advanceUntil(100L)
    assertThat(log).containsExactly("run@100")

    taskFaker.assertNoMoreTasks()
  }

  @Test fun cancelWhileExecutingDoesNothingIfTaskDoesNotRepeat() {
    redQueue.execute("task", 100L) {
      log += "run@${taskFaker.nanoTime}"
      redQueue.cancelAll()
    }

    taskFaker.advanceUntil(0L)
    assertThat(log).isEmpty()

    taskFaker.advanceUntil(100L)
    assertThat(log).containsExactly("run@100")

    taskFaker.assertNoMoreTasks()
  }

  @Test fun cancelWhileExecutingDoesNotStopUncancelableTask() {
    redQueue.schedule(object : Task("task", cancelable = false) {
      val delays = mutableListOf(50L, -1L)
      override fun runOnce(): Long {
        log += "run@${taskFaker.nanoTime}"
        redQueue.cancelAll()
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

  @Test fun interruptingCoordinatorAttemptsToCancelsAndSucceeds() {
    redQueue.execute("task", 100L) {
      log += "run@${taskFaker.nanoTime}"
    }

    taskFaker.advanceUntil(0L)
    assertThat(log).isEmpty()

    taskFaker.interruptCoordinatorThread()

    taskFaker.advanceUntil(0L)
    assertThat(log).isEmpty()

    taskFaker.assertNoMoreTasks()
  }

  @Test fun interruptingCoordinatorAttemptsToCancelsAndFails() {
    redQueue.schedule(object : Task("task", cancelable = false) {
      override fun runOnce(): Long {
        log += "run@${taskFaker.nanoTime}"
        return -1L
      }
    }, 100L)

    taskFaker.advanceUntil(0L)
    assertThat(log).isEmpty()

    taskFaker.interruptCoordinatorThread()

    taskFaker.advanceUntil(0L)
    assertThat(log).isEmpty()

    taskFaker.advanceUntil(100L)
    assertThat(log).containsExactly("run@100")

    taskFaker.assertNoMoreTasks()
  }

  /** Inspect how many runnables have been enqueued. If none then we're truly sequential. */
  @Test fun singleQueueIsSerial() {
    redQueue.execute("task one", 100L) {
      log += "one:run@${taskFaker.nanoTime} parallel=${taskFaker.isParallel}"
    }

    redQueue.execute("task two", 100L) {
      log += "two:run@${taskFaker.nanoTime} parallel=${taskFaker.isParallel}"
    }

    redQueue.execute("task three", 100L) {
      log += "three:run@${taskFaker.nanoTime} parallel=${taskFaker.isParallel}"
    }

    taskFaker.advanceUntil(0L)
    assertThat(log).isEmpty()

    taskFaker.advanceUntil(100L)
    assertThat(log).containsExactly(
        "one:run@100 parallel=false",
        "two:run@100 parallel=false",
        "three:run@100 parallel=false"
    )

    taskFaker.assertNoMoreTasks()
  }

  /** Inspect how many runnables have been enqueued. If non-zero then we're truly parallel. */
  @Test fun differentQueuesAreParallel() {
    redQueue.execute("task one", 100L) {
      log += "one:run@${taskFaker.nanoTime} parallel=${taskFaker.isParallel}"
    }

    blueQueue.execute("task two", 100L) {
      log += "two:run@${taskFaker.nanoTime} parallel=${taskFaker.isParallel}"
    }

    greenQueue.execute("task three", 100L) {
      log += "three:run@${taskFaker.nanoTime} parallel=${taskFaker.isParallel}"
    }

    taskFaker.advanceUntil(0L)
    assertThat(log).isEmpty()

    taskFaker.advanceUntil(100L)
    assertThat(log).containsExactly(
        "one:run@100 parallel=true",
        "two:run@100 parallel=true",
        "three:run@100 parallel=true"
    )

    taskFaker.assertNoMoreTasks()
  }

  /** Test the introspection method [TaskQueue.scheduledTasks]. */
  @Test fun scheduledTasks() {
    redQueue.execute("task one", 100L) {
      // Do nothing.
    }

    redQueue.execute("task two", 200L) {
      // Do nothing.
    }

    assertThat(redQueue.scheduledTasks.toString()).isEqualTo("[task one, task two]")
  }

  /**
   * We don't track the active task in scheduled tasks. This behavior might be a mistake, but it's
   * cumbersome to implement properly because the active task might be a cancel.
   */
  @Test fun scheduledTasksDoesNotIncludeRunningTask() {
    val task = object : Task("task one") {
      val schedules = mutableListOf(200L)
      override fun runOnce(): Long {
        if (schedules.isNotEmpty()) {
          redQueue.schedule(this, schedules.removeAt(0)) // Add it at the end also.
        }
        log += "scheduledTasks=${redQueue.scheduledTasks}"
        return -1L
      }
    }
    redQueue.schedule(task, 100L)

    redQueue.execute("task two", 200L) {
      log += "scheduledTasks=${redQueue.scheduledTasks}"
    }

    taskFaker.advanceUntil(100L)
    assertThat(log).containsExactly(
        "scheduledTasks=[task two, task one]"
    )

    taskFaker.advanceUntil(200L)
    assertThat(log).containsExactly(
        "scheduledTasks=[task two, task one]",
        "scheduledTasks=[task one]"
    )

    taskFaker.advanceUntil(300L)
    assertThat(log).containsExactly(
        "scheduledTasks=[task two, task one]",
        "scheduledTasks=[task one]",
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
    redQueue.execute("task one", 100L) {
      // Do nothing.
    }

    blueQueue.execute("task two", 200L) {
      // Do nothing.
    }

    taskFaker.advanceUntil(0L)
    assertThat(taskRunner.activeQueues()).containsExactly(redQueue, blueQueue)

    taskFaker.advanceUntil(100L)
    assertThat(taskRunner.activeQueues()).containsExactly(blueQueue)

    taskFaker.advanceUntil(200L)
    assertThat(taskRunner.activeQueues()).isEmpty()

    taskFaker.assertNoMoreTasks()
  }

  @Test fun taskNameIsUsedForThreadNameWhenRunning() {
    redQueue.execute("lucky task") {
      log += "run threadName:${Thread.currentThread().name}"
    }

    taskFaker.advanceUntil(0L)
    assertThat(log).containsExactly("run threadName:lucky task")

    taskFaker.assertNoMoreTasks()
  }

  @Test fun shutdownSuccessfullyCancelsScheduledTasks() {
    redQueue.execute("task", 100L) {
      log += "run@${taskFaker.nanoTime}"
    }

    taskFaker.advanceUntil(0L)
    assertThat(log).isEmpty()

    redQueue.shutdown()

    taskFaker.advanceUntil(99L)
    assertThat(log).isEmpty()

    taskFaker.assertNoMoreTasks()
  }

  @Test fun shutdownFailsToCancelsScheduledTasks() {
    redQueue.schedule(object : Task("task", false) {
      override fun runOnce(): Long {
        log += "run@${taskFaker.nanoTime}"
        return 50L
      }
    }, 100L)

    taskFaker.advanceUntil(0L)
    assertThat(log).isEmpty()

    redQueue.shutdown()

    taskFaker.advanceUntil(99L)
    assertThat(log).isEmpty()

    taskFaker.advanceUntil(100L)
    assertThat(log).containsExactly("run@100")

    taskFaker.assertNoMoreTasks()
  }

  @Test fun scheduleDiscardsTaskWhenShutdown() {
    redQueue.shutdown()

    redQueue.execute("task", 100L) {
      // Do nothing.
    }

    taskFaker.assertNoMoreTasks()
  }

  @Test fun scheduleThrowsWhenShutdown() {
    redQueue.shutdown()

    try {
      redQueue.schedule(object : Task("task", cancelable = false) {
        override fun runOnce(): Long {
          return -1L
        }
      }, 100L)
      fail()
    } catch (_: RejectedExecutionException) {
    }

    taskFaker.assertNoMoreTasks()
  }
}
