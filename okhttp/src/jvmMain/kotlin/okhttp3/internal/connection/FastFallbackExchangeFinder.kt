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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import okhttp3.internal.concurrent.Task
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.connection.RoutePlanner.ConnectResult
import okhttp3.internal.connection.RoutePlanner.Plan
import okhttp3.internal.okHttpName

/**
 * Speculatively connects to each IP address of a target address, returning as soon as one of them
 * connects successfully. This kicks off new attempts every 250 ms until a connect succeeds.
 */
internal class FastFallbackExchangeFinder(
  override val routePlanner: RoutePlanner,
  private val taskRunner: TaskRunner,
) : ExchangeFinder {
  private val connectDelayNanos = TimeUnit.MILLISECONDS.toNanos(250L)
  private var nextTcpConnectAtNanos = Long.MIN_VALUE

  /**
   * Plans currently being connected, and that will later be added to [connectResults]. This is
   * mutated by the call thread only. If is accessed by background connect threads.
   */
  private val tcpConnectsInFlight = CopyOnWriteArrayList<Plan>()

  /**
   * These are retries of plans that were canceled when they lost a race. If the race's winner ends
   * up not working out, this is what we'll attempt first.
   */
  private val deferredPlans = ArrayDeque<Plan>()

  /**
   * Results are posted here as they occur. The find job is done when either one plan completes
   * successfully or all plans fail.
   */
  private val connectResults = taskRunner.backend.decorate(LinkedBlockingDeque<ConnectResult>())

  override fun find(): RealConnection {
    var firstException: IOException? = null
    try {
      while (tcpConnectsInFlight.isNotEmpty() ||
        deferredPlans.isNotEmpty() ||
        routePlanner.hasNext()
      ) {
        if (routePlanner.isCanceled()) throw IOException("Canceled")

        // Launch a new connection if we're ready to.
        val now = taskRunner.backend.nanoTime()
        var awaitTimeoutNanos = nextTcpConnectAtNanos - now
        if (tcpConnectsInFlight.isEmpty() || awaitTimeoutNanos <= 0) {
          launchTcpConnect()
          nextTcpConnectAtNanos = now + connectDelayNanos
          awaitTimeoutNanos = connectDelayNanos
        }

        // Wait for an in-flight connect to complete or fail.
        var connectResult = awaitTcpConnect(awaitTimeoutNanos, TimeUnit.NANOSECONDS) ?: continue

        if (connectResult.isSuccess) {
          // We have a connected TCP connection. Cancel and defer the racing connects that all lost.
          cancelInFlightConnects()

          // Finish connecting. We won't have to if the winner is from the connection pool.
          if (!connectResult.plan.isReady) {
            connectResult = connectResult.plan.connectTlsEtc()
          }

          if (connectResult.isSuccess) {
            return connectResult.plan.handleSuccess()
          }
        }

        val throwable = connectResult.throwable
        if (throwable != null) {
          if (throwable !is IOException) throw throwable
          if (firstException == null) {
            firstException = throwable
          } else {
            firstException.addSuppressed(throwable)
          }
        }

        val nextPlan = connectResult.nextPlan
        if (nextPlan != null) {
          // Try this plan's successor before deferred plans because it won the race!
          deferredPlans.addFirst(nextPlan)
        }
      }
    } finally {
      cancelInFlightConnects()
    }

    throw firstException!!
  }

  private fun launchTcpConnect() {
    val plan = when {
      deferredPlans.isNotEmpty() -> {
        deferredPlans.removeFirst()
      }
      routePlanner.hasNext() -> {
        try {
          routePlanner.plan()
        } catch (e: Throwable) {
          FailedPlan(e)
        }
      }
      else -> return // Nothing further to try.
    }

    tcpConnectsInFlight += plan

    // Already connected? Enqueue the result immediately.
    if (plan.isReady) {
      connectResults.put(ConnectResult(plan))
      return
    }

    // Already failed? Enqueue the result immediately.
    if (plan is FailedPlan) {
      connectResults.put(plan.result)
      return
    }

    // Connect TCP asynchronously.
    val taskName = "$okHttpName connect ${routePlanner.address.url.redact()}"
    taskRunner.newQueue().schedule(object : Task(taskName) {
      override fun runOnce(): Long {
        val connectResult = try {
          plan.connectTcp()
        } catch (e: Throwable) {
          ConnectResult(plan, throwable = e)
        }
        // Only post a result if this hasn't since been canceled.
        if (plan in tcpConnectsInFlight) {
          connectResults.put(connectResult)
        }
        return -1L
      }
    })
  }

  private fun awaitTcpConnect(timeout: Long, unit: TimeUnit): ConnectResult? {
    if (tcpConnectsInFlight.isEmpty()) return null

    val result = connectResults.poll(timeout, unit) ?: return null

    tcpConnectsInFlight.remove(result.plan)

    return result
  }

  private fun cancelInFlightConnects() {
    for (plan in tcpConnectsInFlight) {
      plan.cancel()
      val retry = plan.retry() ?: continue
      deferredPlans += retry
    }
    tcpConnectsInFlight.clear()
  }
}
