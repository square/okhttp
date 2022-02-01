/*
 * Copyright (C) 2022 Square, Inc.
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
package okhttp3.internal.connection

import java.io.IOException
import okhttp3.FakeRoutePlanner
import okhttp3.internal.concurrent.TaskFaker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Unit test for [FastFallbackExchangeFinder] implementation details.
 *
 * This test uses [TaskFaker] to deterministically test racy code. Each function in this test has
 * the same structure:
 *
 *  * prepare a set of plans, each with a predictable connect delay
 *  * attempt to find a connection
 *  * step through time, asserting that the expected side effects are performed.
 */
internal class FastFallbackExchangeFinderTest {
  private val taskFaker = TaskFaker()
  private val taskRunner = taskFaker.taskRunner
  private val routePlanner = FakeRoutePlanner(taskFaker)
  private val finder = FastFallbackExchangeFinder(routePlanner, taskRunner)

  @AfterEach
  fun tearDown() {
    taskFaker.close()
    routePlanner.close()
  }

  @Test
  fun takeConnectedConnection() {
    val plan0 = routePlanner.addPlan()
    plan0.isConnected = true

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan0.connection)
    }

    taskFaker.runTasks()
    taskFaker.assertNoMoreTasks()
    assertThat(takeEvent()).isEqualTo("take plan 0")
    assertThat(takeEvent()).isNull()
  }

  @Test
  fun takeConnectingConnection() {
    val plan0 = routePlanner.addPlan()
    plan0.connectDelayNanos = 240.ms

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan0.connection)
    }

    taskFaker.runTasks()
    assertThat(takeEvent()).isEqualTo("take plan 0")
    assertThat(takeEvent()).isEqualTo("plan 0 connecting...")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(240.ms)
    assertThat(takeEvent()).isEqualTo("plan 0 connected")
    assertThat(takeEvent()).isNull()
  }

  @Test
  fun firstPlanConnectedBeforeSecondPlan() {
    val plan0 = routePlanner.addPlan()
    plan0.connectDelayNanos = 260.ms
    val plan1 = routePlanner.addPlan()
    plan1.connectDelayNanos = 20.ms // Connect at time = 270 ms.

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan0.connection)
    }

    taskFaker.runTasks()
    assertThat(takeEvent()).isEqualTo("take plan 0")
    assertThat(takeEvent()).isEqualTo("plan 0 connecting...")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(250.ms)
    assertThat(takeEvent()).isEqualTo("take plan 1")
    assertThat(takeEvent()).isEqualTo("plan 1 connecting...")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(260.ms)
    assertThat(takeEvent()).isEqualTo("plan 0 connected")
    assertThat(takeEvent()).isEqualTo("plan 1 cancel")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(270.ms)
    assertThat(takeEvent()).isEqualTo("plan 1 connect canceled")
    assertThat(routePlanner.events.poll()).isNull()
  }

  @Test
  fun secondPlanAlreadyConnected() {
    val plan0 = routePlanner.addPlan()
    plan0.connectDelayNanos = 260.ms
    val plan1 = routePlanner.addPlan()
    plan1.isConnected = true

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan1.connection)
    }

    taskFaker.runTasks()
    assertThat(routePlanner.events.poll()).isEqualTo("take plan 0")
    assertThat(takeEvent()).isEqualTo("plan 0 connecting...")
    assertThat(routePlanner.events.poll()).isNull()

    taskFaker.advanceUntil(250.ms)
    assertThat(routePlanner.events.poll()).isEqualTo("take plan 1")
    assertThat(routePlanner.events.poll()).isEqualTo("plan 0 cancel")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(260.ms)
    assertThat(takeEvent()).isEqualTo("plan 0 connect canceled")
    assertThat(takeEvent()).isNull()
  }

  @Test
  fun secondPlanConnectedBeforeFirstPlan() {
    val plan0 = routePlanner.addPlan()
    plan0.connectDelayNanos = 270.ms
    val plan1 = routePlanner.addPlan()
    plan1.connectDelayNanos = 10.ms // Connect at time = 260 ms.

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan1.connection)
    }

    taskFaker.runTasks()
    assertThat(takeEvent()).isEqualTo("take plan 0")
    assertThat(takeEvent()).isEqualTo("plan 0 connecting...")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(250.ms)
    assertThat(takeEvent()).isEqualTo("take plan 1")
    assertThat(takeEvent()).isEqualTo("plan 1 connecting...")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(260.ms)
    assertThat(takeEvent()).isEqualTo("plan 1 connected")
    assertThat(routePlanner.events.poll()).isEqualTo("plan 0 cancel")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(270.ms)
    assertThat(takeEvent()).isEqualTo("plan 0 connect canceled")
    assertThat(takeEvent()).isNull()
  }

  @Test
  fun thirdPlanAlreadyConnected() {
    val plan0 = routePlanner.addPlan()
    plan0.connectDelayNanos = 520.ms
    val plan1 = routePlanner.addPlan()
    plan1.connectDelayNanos = 260.ms // Connect completes at 510ms.
    val plan2 = routePlanner.addPlan()
    plan2.isConnected = true

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan2.connection)
    }

    taskFaker.runTasks()
    assertThat(routePlanner.events.poll()).isEqualTo("take plan 0")
    assertThat(takeEvent()).isEqualTo("plan 0 connecting...")
    assertThat(routePlanner.events.poll()).isNull()

    taskFaker.advanceUntil(250.ms)
    assertThat(takeEvent()).isEqualTo("take plan 1")
    assertThat(takeEvent()).isEqualTo("plan 1 connecting...")
    assertThat(routePlanner.events.poll()).isNull()

    taskFaker.advanceUntil(500.ms)
    assertThat(takeEvent()).isEqualTo("take plan 2")
    assertThat(takeEvent()).isEqualTo("plan 0 cancel")
    assertThat(routePlanner.events.poll()).isEqualTo("plan 1 cancel")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(510.ms)
    assertThat(takeEvent()).isEqualTo("plan 1 connect canceled")
    assertThat(routePlanner.events.poll()).isNull()

    taskFaker.advanceUntil(520.ms)
    assertThat(takeEvent()).isEqualTo("plan 0 connect canceled")
    assertThat(routePlanner.events.poll()).isNull()
  }

  @Test
  fun takeMultipleConnections() {
    val plan0 = routePlanner.addPlan()
    plan0.isConnected = true
    val plan1 = routePlanner.addPlan()
    plan1.isConnected = true

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan0.connection)
      val result1 = finder.find()
      assertThat(result1).isEqualTo(plan1.connection)
    }

    taskFaker.runTasks()
    taskFaker.assertNoMoreTasks()
    assertThat(takeEvent()).isEqualTo("take plan 0")
    assertThat(takeEvent()).isEqualTo("take plan 1")
    assertThat(takeEvent()).isNull()
  }

  @Test
  fun firstConnectionFailsAndNoOthersExist() {
    val plan0 = routePlanner.addPlan()
    plan0.connectThrowable = IOException("boom!")

    taskRunner.newQueue().execute("connect") {
      try {
        finder.find()
        fail()
      } catch (e: IOException) {
        assertThat(e).hasMessage("boom!")
      }
    }

    taskFaker.runTasks()
    taskFaker.assertNoMoreTasks()
    assertThat(takeEvent()).isEqualTo("take plan 0")
    assertThat(takeEvent()).isEqualTo("plan 0 connecting...")
    assertThat(takeEvent()).isEqualTo("plan 0 connect failed")
    assertThat(takeEvent()).isEqualTo("tracking failure: java.io.IOException: boom!")
    assertThat(takeEvent()).isNull()
  }

  @Test
  fun firstConnectionFailsToConnectAndSecondSucceeds() {
    val plan0 = routePlanner.addPlan()
    plan0.connectThrowable = IOException("boom!")
    val plan1 = routePlanner.addPlan()
    plan1.isConnected = true

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan1.connection)
    }

    taskFaker.runTasks()
    taskFaker.assertNoMoreTasks()
    assertThat(takeEvent()).isEqualTo("take plan 0")
    assertThat(takeEvent()).isEqualTo("plan 0 connecting...")
    assertThat(takeEvent()).isEqualTo("plan 0 connect failed")
    assertThat(takeEvent()).isEqualTo("tracking failure: java.io.IOException: boom!")
    assertThat(takeEvent()).isEqualTo("take plan 1")
    assertThat(takeEvent()).isNull()
  }

  @Test
  fun firstConnectionFailsToConnectAndSecondFailureIsSuppressedException() {
    val plan0 = routePlanner.addPlan()
    plan0.connectThrowable = IOException("boom 0!")
    val plan1 = routePlanner.addPlan()
    plan1.connectThrowable = IOException("boom 1!")

    taskRunner.newQueue().execute("connect") {
      try {
        finder.find()
        fail()
      } catch (e: IOException) {
        assertThat(e).hasMessage("boom 0!")
        assertThat(e.suppressed.single()).hasMessage("boom 1!")
      }
    }

    taskFaker.runTasks()
    taskFaker.assertNoMoreTasks()
    assertThat(takeEvent()).isEqualTo("take plan 0")
    assertThat(takeEvent()).isEqualTo("plan 0 connecting...")
    assertThat(takeEvent()).isEqualTo("plan 0 connect failed")
    assertThat(takeEvent()).isEqualTo("tracking failure: java.io.IOException: boom 0!")
    assertThat(takeEvent()).isEqualTo("take plan 1")
    assertThat(takeEvent()).isEqualTo("plan 1 connecting...")
    assertThat(takeEvent()).isEqualTo("plan 1 connect failed")
    assertThat(takeEvent()).isEqualTo("tracking failure: java.io.IOException: boom 1!")
    assertThat(takeEvent()).isNull()
  }

  @Test
  fun firstConnectionCrashesWithUncheckedException() {
    val plan0 = routePlanner.addPlan()
    plan0.connectThrowable = IllegalStateException("boom!")
    routePlanner.addPlan() // This plan should not be used.

    taskRunner.newQueue().execute("connect") {
      try {
        finder.find()
        fail()
      } catch (e: IllegalStateException) {
        assertThat(e).hasMessage("boom!")
      }
    }

    taskFaker.runTasks()
    taskFaker.assertNoMoreTasks()
    assertThat(takeEvent()).isEqualTo("take plan 0")
    assertThat(takeEvent()).isEqualTo("plan 0 connecting...")
    assertThat(takeEvent()).isEqualTo("plan 0 connect failed")
    assertThat(takeEvent()).isNull()
  }

  private fun takeEvent() = routePlanner.events.poll()

  private val Int.ms: Long
    get() = this * 1_000_000L
}
