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
  private val routePlanner: RoutePlanner,
  private val taskRunner: TaskRunner,
) {
  private val connectDelayMillis = 250L

  /** Plans currently being connected, and that will later be added to [connectResults]. */
  private var connectsInFlight = CopyOnWriteArrayList<Plan>()

  /**
   * Results are posted here as they occur. The find job is done when either one plan completes
   * successfully or all plans fail.
   */
  private val connectResults = taskRunner.backend.decorate(LinkedBlockingDeque<ConnectResult>())

  /** Exceptions accumulate here. */
  private var firstException: IOException? = null

  /** True until we've launched all the connects we'll ever launch. */
  private var morePlansExist = true

  fun find(): RealConnection {
    try {
      while (morePlansExist || connectsInFlight.isNotEmpty()) {
        if (routePlanner.isCanceled()) throw IOException("Canceled")

        launchConnect()

        val connection = awaitConnection()
        if (connection != null) return connection

        morePlansExist = morePlansExist && routePlanner.hasMoreRoutes()
      }

      throw firstException!!
    } finally {
      for (plan in connectsInFlight) {
        plan.cancel()
      }
    }
  }

  private fun launchConnect() {
    if (!morePlansExist) return

    val plan = try {
      routePlanner.plan()
    } catch (e: IOException) {
      trackFailure(e)
      return
    }

    connectsInFlight += plan

    // Already connected? Enqueue the result immediately.
    if (plan.isConnected) {
      connectResults.put(ConnectResult(plan))
      return
    }

    // Connect asynchronously.
    val taskName = "$okHttpName connect ${routePlanner.address.url.redact()}"
    taskRunner.newQueue().schedule(object : Task(taskName) {
      override fun runOnce(): Long {
        val connectResult = try {
          connectAndDoRetries()
        } catch (e: Throwable) {
          ConnectResult(plan, throwable = e)
        }
        connectResults.put(connectResult)
        return -1L
      }

      private fun connectAndDoRetries(): ConnectResult {
        var firstException: Throwable? = null
        var currentPlan = plan
        while (true) {
          val connectResult = currentPlan.connect()

          if (connectResult.throwable == null) {
            if (connectResult.nextPlan == null) return connectResult // Success.
          } else {
            if (firstException == null) {
              firstException = connectResult.throwable
            } else {
              firstException.addSuppressed(connectResult.throwable)
            }

            // Fail if there's no next plan, or if this failure exception is not recoverable.
            if (connectResult.nextPlan == null || connectResult.throwable !is IOException) break
          }

          // Replace the currently-running plan with its successor.
          connectsInFlight.add(connectResult.nextPlan)
          connectsInFlight.remove(currentPlan)
          currentPlan = connectResult.nextPlan
        }

        return ConnectResult(currentPlan, throwable = firstException)
      }
    })
  }

  private fun awaitConnection(): RealConnection? {
    if (connectsInFlight.isEmpty()) return null

    val completed = connectResults.poll(connectDelayMillis, TimeUnit.MILLISECONDS) ?: return null

    connectsInFlight.remove(completed.plan)

    val exception = completed.throwable
    if (exception is IOException) {
      trackFailure(exception)
      return null
    } else if (exception != null) {
      throw exception
    }

    return completed.plan.handleSuccess()
  }

  private fun trackFailure(exception: IOException) {
    routePlanner.trackFailure(exception)

    if (firstException == null) {
      firstException = exception
    } else {
      firstException!!.addSuppressed(exception)
    }
  }
}
