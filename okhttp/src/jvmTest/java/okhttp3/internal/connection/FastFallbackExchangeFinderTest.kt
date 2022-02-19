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
import java.net.UnknownServiceException
import okhttp3.FakeRoutePlanner
import okhttp3.FakeRoutePlanner.ConnectState.TLS_CONNECTED
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
    plan0.connectState = TLS_CONNECTED

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
    plan0.tcpConnectDelayNanos = 240.ms

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan0.connection)
    }

    taskFaker.runTasks()
    assertThat(takeEvent()).isEqualTo("take plan 0")
    assertThat(takeEvent()).isEqualTo("plan 0 TCP connecting...")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(240.ms)
    assertThat(takeEvent()).isEqualTo("plan 0 TCP connected")
    assertThat(takeEvent()).isEqualTo("plan 0 TLS connecting...")
    assertThat(takeEvent()).isEqualTo("plan 0 TLS connected")
    assertThat(takeEvent()).isNull()
  }

  @Test
  fun firstPlanConnectedBeforeSecondPlan() {
    val plan0 = routePlanner.addPlan()
    plan0.tcpConnectDelayNanos = 260.ms
    val plan1 = routePlanner.addPlan()
    plan1.tcpConnectDelayNanos = 20.ms // Connect at time = 270 ms.

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan0.connection)
    }

    taskFaker.runTasks()
    assertThat(takeEvent()).isEqualTo("take plan 0")
    assertThat(takeEvent()).isEqualTo("plan 0 TCP connecting...")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(250.ms)
    assertThat(takeEvent()).isEqualTo("take plan 1")
    assertThat(takeEvent()).isEqualTo("plan 1 TCP connecting...")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(260.ms)
    assertThat(takeEvent()).isEqualTo("plan 0 TCP connected")
    assertThat(takeEvent()).isEqualTo("plan 1 cancel")
    assertThat(takeEvent()).isEqualTo("plan 0 TLS connecting...")
    assertThat(takeEvent()).isEqualTo("plan 0 TLS connected")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(270.ms)
    assertThat(takeEvent()).isEqualTo("plan 1 TCP connect canceled")
    assertThat(routePlanner.events.poll()).isNull()
  }

  @Test
  fun secondPlanAlreadyConnected() {
    val plan0 = routePlanner.addPlan()
    plan0.tcpConnectDelayNanos = 260.ms
    val plan1 = routePlanner.addPlan()
    plan1.connectState = TLS_CONNECTED

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan1.connection)
    }

    taskFaker.runTasks()
    assertThat(routePlanner.events.poll()).isEqualTo("take plan 0")
    assertThat(takeEvent()).isEqualTo("plan 0 TCP connecting...")
    assertThat(routePlanner.events.poll()).isNull()

    taskFaker.advanceUntil(250.ms)
    assertThat(routePlanner.events.poll()).isEqualTo("take plan 1")
    assertThat(routePlanner.events.poll()).isEqualTo("plan 0 cancel")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(260.ms)
    assertThat(takeEvent()).isEqualTo("plan 0 TCP connect canceled")
    assertThat(takeEvent()).isNull()
  }

  @Test
  fun secondPlanConnectedBeforeFirstPlan() {
    val plan0 = routePlanner.addPlan()
    plan0.tcpConnectDelayNanos = 270.ms
    val plan1 = routePlanner.addPlan()
    plan1.tcpConnectDelayNanos = 10.ms // Connect at time = 260 ms.

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan1.connection)
    }

    taskFaker.runTasks()
    assertThat(takeEvent()).isEqualTo("take plan 0")
    assertThat(takeEvent()).isEqualTo("plan 0 TCP connecting...")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(250.ms)
    assertThat(takeEvent()).isEqualTo("take plan 1")
    assertThat(takeEvent()).isEqualTo("plan 1 TCP connecting...")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(260.ms)
    assertThat(takeEvent()).isEqualTo("plan 1 TCP connected")
    assertThat(takeEvent()).isEqualTo("plan 0 cancel")
    assertThat(takeEvent()).isEqualTo("plan 1 TLS connecting...")
    assertThat(takeEvent()).isEqualTo("plan 1 TLS connected")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(270.ms)
    assertThat(takeEvent()).isEqualTo("plan 0 TCP connect canceled")
    assertThat(takeEvent()).isNull()
  }

  @Test
  fun thirdPlanAlreadyConnected() {
    val plan0 = routePlanner.addPlan()
    plan0.tcpConnectDelayNanos = 520.ms
    val plan1 = routePlanner.addPlan()
    plan1.tcpConnectDelayNanos = 260.ms // Connect completes at 510ms.
    val plan2 = routePlanner.addPlan()
    plan2.connectState = TLS_CONNECTED

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan2.connection)
    }

    taskFaker.runTasks()
    assertThat(routePlanner.events.poll()).isEqualTo("take plan 0")
    assertThat(takeEvent()).isEqualTo("plan 0 TCP connecting...")
    assertThat(routePlanner.events.poll()).isNull()

    taskFaker.advanceUntil(250.ms)
    assertThat(takeEvent()).isEqualTo("take plan 1")
    assertThat(takeEvent()).isEqualTo("plan 1 TCP connecting...")
    assertThat(routePlanner.events.poll()).isNull()

    taskFaker.advanceUntil(500.ms)
    assertThat(takeEvent()).isEqualTo("take plan 2")
    assertThat(takeEvent()).isEqualTo("plan 0 cancel")
    assertThat(routePlanner.events.poll()).isEqualTo("plan 1 cancel")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(510.ms)
    assertThat(takeEvent()).isEqualTo("plan 1 TCP connect canceled")
    assertThat(routePlanner.events.poll()).isNull()

    taskFaker.advanceUntil(520.ms)
    assertThat(takeEvent()).isEqualTo("plan 0 TCP connect canceled")
    assertThat(routePlanner.events.poll()).isNull()
  }

  @Test
  fun takeMultipleConnections() {
    val plan0 = routePlanner.addPlan()
    plan0.connectState = TLS_CONNECTED
    val plan1 = routePlanner.addPlan()
    plan1.connectState = TLS_CONNECTED

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
    plan0.tcpConnectThrowable = IOException("boom!")

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
    assertThat(takeEvent()).isEqualTo("plan 0 TCP connecting...")
    assertThat(takeEvent()).isEqualTo("plan 0 TCP connect failed")
    assertThat(takeEvent()).isEqualTo("tracking failure: java.io.IOException: boom!")
    assertThat(takeEvent()).isNull()
  }

  @Test
  fun firstConnectionFailsToConnectAndSecondSucceeds() {
    val plan0 = routePlanner.addPlan()
    plan0.tcpConnectThrowable = IOException("boom!")
    val plan1 = routePlanner.addPlan()
    plan1.connectState = TLS_CONNECTED

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan1.connection)
    }

    taskFaker.runTasks()
    taskFaker.assertNoMoreTasks()
    assertThat(takeEvent()).isEqualTo("take plan 0")
    assertThat(takeEvent()).isEqualTo("plan 0 TCP connecting...")
    assertThat(takeEvent()).isEqualTo("plan 0 TCP connect failed")
    assertThat(takeEvent()).isEqualTo("tracking failure: java.io.IOException: boom!")
    assertThat(takeEvent()).isEqualTo("take plan 1")
    assertThat(takeEvent()).isNull()
  }

  @Test
  fun firstConnectionFailsToConnectAndSecondFailureIsSuppressedException() {
    val plan0 = routePlanner.addPlan()
    plan0.tcpConnectThrowable = IOException("boom 0!")
    val plan1 = routePlanner.addPlan()
    plan1.tcpConnectThrowable = IOException("boom 1!")

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
    assertThat(takeEvent()).isEqualTo("plan 0 TCP connecting...")
    assertThat(takeEvent()).isEqualTo("plan 0 TCP connect failed")
    assertThat(takeEvent()).isEqualTo("tracking failure: java.io.IOException: boom 0!")
    assertThat(takeEvent()).isEqualTo("take plan 1")
    assertThat(takeEvent()).isEqualTo("plan 1 TCP connecting...")
    assertThat(takeEvent()).isEqualTo("plan 1 TCP connect failed")
    assertThat(takeEvent()).isEqualTo("tracking failure: java.io.IOException: boom 1!")
    assertThat(takeEvent()).isNull()
  }

  @Test
  fun firstConnectionCrashesWithUncheckedException() {
    val plan0 = routePlanner.addPlan()
    plan0.tcpConnectThrowable = IllegalStateException("boom!")
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
    assertThat(takeEvent()).isEqualTo("plan 0 TCP connecting...")
    assertThat(takeEvent()).isEqualTo("plan 0 TCP connect failed")
    assertThat(takeEvent()).isNull()
  }

  @Test
  fun routePlannerPlanThrowsOnOnlyPlan() {
    val plan0 = routePlanner.addPlan()
    plan0.planningThrowable = UnknownServiceException("boom!")

    taskRunner.newQueue().execute("connect") {
      try {
        finder.find()
        fail()
      } catch (e: UnknownServiceException) {
        assertThat(e).hasMessage("boom!")
      }
    }

    taskFaker.runTasks()
    taskFaker.assertNoMoreTasks()
    assertThat(takeEvent()).isEqualTo("take plan 0")
    assertThat(takeEvent()).isEqualTo("tracking failure: ${UnknownServiceException("boom!")}")
    assertThat(takeEvent()).isNull()
  }

  @Test
  fun recoversAfterFirstPlanCallThrows() {
    val plan0 = routePlanner.addPlan()
    plan0.planningThrowable = UnknownServiceException("boom!")
    val plan1 = routePlanner.addPlan()

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan1.connection)
    }

    taskFaker.runTasks()
    taskFaker.assertNoMoreTasks()
    assertThat(takeEvent()).isEqualTo("take plan 0")
    assertThat(takeEvent()).isEqualTo("tracking failure: ${UnknownServiceException("boom!")}")
    assertThat(takeEvent()).isEqualTo("take plan 1")
    assertThat(takeEvent()).isEqualTo("plan 1 TCP connecting...")
    assertThat(takeEvent()).isEqualTo("plan 1 TCP connected")
    assertThat(takeEvent()).isEqualTo("plan 1 TLS connecting...")
    assertThat(takeEvent()).isEqualTo("plan 1 TLS connected")
    assertThat(takeEvent()).isNull()
  }

  @Test
  fun retryConnectionThatLostTcpRaceAfterWinnersTlsFails() {
    val plan0 = routePlanner.addPlan()
    plan0.tcpConnectDelayNanos = 270.ms

    val plan1 = routePlanner.addPlan()
    plan1.tcpConnectDelayNanos = 10.ms // TCP connect at time = 260 ms.
    plan1.tlsConnectThrowable = IOException("boom!")

    val plan2 = plan0.createRetry()

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan2.connection)
    }

    taskFaker.runTasks()
    assertThat(takeEvent()).isEqualTo("take plan 0")
    assertThat(takeEvent()).isEqualTo("plan 0 TCP connecting...")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(250.ms)
    taskFaker.runTasks()
    assertThat(takeEvent()).isEqualTo("take plan 1")
    assertThat(takeEvent()).isEqualTo("plan 1 TCP connecting...")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(260.ms)
    taskFaker.runTasks()
    assertThat(takeEvent()).isEqualTo("plan 1 TCP connected")
    assertThat(takeEvent()).isEqualTo("plan 0 cancel")
    assertThat(takeEvent()).isEqualTo("plan 1 TLS connecting...")
    assertThat(takeEvent()).isEqualTo("plan 1 TLS connect failed")
    assertThat(takeEvent()).isEqualTo("tracking failure: java.io.IOException: boom!")
    assertThat(takeEvent()).isEqualTo("plan 2 TCP connecting...")
    assertThat(takeEvent()).isEqualTo("plan 2 TCP connected")
    assertThat(takeEvent()).isEqualTo("plan 2 TLS connecting...")
    assertThat(takeEvent()).isEqualTo("plan 2 TLS connected")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(270.ms)
    taskFaker.runTasks()
    assertThat(takeEvent()).isEqualTo("plan 0 TCP connect canceled")
    assertThat(takeEvent()).isNull()
  }

  @Test
  fun losingPlanDoesNotConnectTls() {
    val plan0 = routePlanner.addPlan()
    plan0.tcpConnectDelayNanos = 270.ms
    val plan1 = routePlanner.addPlan()
    plan1.tcpConnectDelayNanos = 10.ms // Connect at time = 260 ms.
    plan1.tlsConnectDelayNanos = 20.ms // Connect at time = 280 ms.

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan0.connection)
    }

    taskFaker.runTasks()
    assertThat(takeEvent()).isEqualTo("take plan 0")
    assertThat(takeEvent()).isEqualTo("plan 0 TCP connecting...")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(250.ms)
    assertThat(takeEvent()).isEqualTo("take plan 1")
    assertThat(takeEvent()).isEqualTo("plan 1 TCP connecting...")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(260.ms)
    assertThat(takeEvent()).isEqualTo("plan 1 TCP connected")
    assertThat(takeEvent()).isEqualTo("plan 0 cancel")
    assertThat(takeEvent()).isEqualTo("plan 1 TLS connecting...")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(270.ms)
    assertThat(takeEvent()).isEqualTo("plan 0 TCP connect canceled")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(280.ms)
    assertThat(takeEvent()).isEqualTo("plan 1 TLS connected")
    assertThat(takeEvent()).isNull()

    assertThat(routePlanner.events.poll()).isNull()
  }

  @Test
  fun tcpConnectFollowUpPlanUsed() {
    val plan0 = routePlanner.addPlan()
    val plan1 = plan0.createConnectTcpNextPlan()

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan1.connection)
    }

    taskFaker.runTasks()
    assertThat(takeEvent()).isEqualTo("take plan 0")
    assertThat(takeEvent()).isEqualTo("plan 0 TCP connecting...")
    assertThat(takeEvent()).isEqualTo("plan 0 needs follow-up")
    assertThat(takeEvent()).isEqualTo("plan 1 TCP connecting...")
    assertThat(takeEvent()).isEqualTo("plan 1 TCP connected")
    assertThat(takeEvent()).isEqualTo("plan 1 TLS connecting...")
    assertThat(takeEvent()).isEqualTo("plan 1 TLS connected")
    assertThat(takeEvent()).isNull()
  }

  @Test
  fun tlsConnectFollowUpPlanUsed() {
    val plan0 = routePlanner.addPlan()
    val plan1 = plan0.createConnectTlsNextPlan()

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan1.connection)
    }

    taskFaker.runTasks()
    assertThat(takeEvent()).isEqualTo("take plan 0")
    assertThat(takeEvent()).isEqualTo("plan 0 TCP connecting...")
    assertThat(takeEvent()).isEqualTo("plan 0 TCP connected")
    assertThat(takeEvent()).isEqualTo("plan 0 TLS connecting...")
    assertThat(takeEvent()).isEqualTo("plan 0 needs follow-up")
    assertThat(takeEvent()).isEqualTo("plan 1 TCP connecting...")
    assertThat(takeEvent()).isEqualTo("plan 1 TCP connected")
    assertThat(takeEvent()).isEqualTo("plan 1 TLS connecting...")
    assertThat(takeEvent()).isEqualTo("plan 1 TLS connected")
    assertThat(takeEvent()).isNull()
  }

  /**
   * This test performs two races:
   *
   *  * The first race is between plan0 and plan1, with a 250 ms head start for plan0.
   *  * The second race is between plan2 and plan3, with a 250 ms head start for plan2.
   *
   * We get plan0 and plan1 from the route planner.
   * We get plan2 as a follow-up to plan1, typically retry the same IP but different TLS.
   * We get plan3 as a retry of plan0, which was canceled when it lost the race.
   *
   * This test confirms that we prefer to do the TLS follow-up (plan2) before the TCP retry (plan3).
   * It also confirms we enforce the 250 ms delay in each race.
   */
  @Test
  fun tcpConnectionsRaceAfterTlsFails() {
    val plan0 = routePlanner.addPlan()
    plan0.tcpConnectDelayNanos = 280.ms

    val plan1 = routePlanner.addPlan()
    plan1.tcpConnectDelayNanos = 10.ms // Connect at time = 260 ms.
    plan1.tlsConnectDelayNanos = 10.ms // Connect at time = 270 ms.
    plan1.tlsConnectThrowable = IOException("boom!")

    val plan2 = plan1.createConnectTlsNextPlan()
    plan2.tcpConnectDelayNanos = 270.ms // Connect at time = 540 ms.

    val plan3 = plan0.createRetry()
    plan3.tcpConnectDelayNanos = 10.ms // Connect at time = 530 ms.

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan3.connection)
    }

    taskFaker.runTasks()
    assertThat(takeEvent()).isEqualTo("take plan 0")
    assertThat(takeEvent()).isEqualTo("plan 0 TCP connecting...")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(250.ms)
    taskFaker.runTasks()
    assertThat(takeEvent()).isEqualTo("take plan 1")
    assertThat(takeEvent()).isEqualTo("plan 1 TCP connecting...")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(260.ms)
    taskFaker.runTasks()
    assertThat(takeEvent()).isEqualTo("plan 1 TCP connected")
    assertThat(takeEvent()).isEqualTo("plan 0 cancel")
    assertThat(takeEvent()).isEqualTo("plan 1 TLS connecting...")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(270.ms)
    taskFaker.runTasks()
    assertThat(takeEvent()).isEqualTo("plan 1 TLS connect failed")
    assertThat(takeEvent()).isEqualTo("tracking failure: ${IOException("boom!")}")
    assertThat(takeEvent()).isEqualTo("plan 2 TCP connecting...")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(280.ms)
    taskFaker.runTasks()
    assertThat(takeEvent()).isEqualTo("plan 0 TCP connect canceled")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(520.ms)
    taskFaker.runTasks()
    assertThat(takeEvent()).isEqualTo("plan 3 TCP connecting...")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(530.ms)
    taskFaker.runTasks()
    assertThat(takeEvent()).isEqualTo("plan 3 TCP connected")
    assertThat(takeEvent()).isEqualTo("plan 2 cancel")
    assertThat(takeEvent()).isEqualTo("plan 3 TLS connecting...")
    assertThat(takeEvent()).isEqualTo("plan 3 TLS connected")
    assertThat(takeEvent()).isNull()

    taskFaker.advanceUntil(540.ms)
    taskFaker.runTasks()
    assertThat(takeEvent()).isEqualTo("plan 2 TCP connect canceled")
    assertThat(takeEvent()).isNull()
  }

  @Test
  fun cancelEarlyDuringTcpConnectDoesntLeak() {
    // TODO(jwilson): change ConnectPlan.cancel() to work even if rawSocket hasn't been created yet.
  }

  @Test
  fun routeFailureStatisticsAreTrackedPerRoute() {
    // TODO(jwilson): we call resetStatistics() in the wrong phrase for racy connects.
  }

  private fun takeEvent() = routePlanner.events.poll()

  private val Int.ms: Long
    get() = this * 1_000_000L
}
