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
package okhttp3

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.LinkedBlockingDeque
import okhttp3.internal.concurrent.TaskFaker
import okhttp3.internal.connection.RoutePlanner
import okhttp3.internal.connection.RoutePlanner.ConnectResult

class FakeRoutePlanner(
  private val taskFaker: TaskFaker,
) : RoutePlanner, Closeable {
  /**
   * Note that we don't use the same [TaskFaker] for this factory. That way off-topic tasks like
   * connection pool maintenance tasks don't add noise to route planning tests.
   */
  private val factory = TestValueFactory()

  private val pool = factory.newConnectionPool()

  val events = LinkedBlockingDeque<String>()
  var canceled = false
  var hasFailure = false
  private var nextPlanId = 0
  private var nextPlanIndex = 0
  private val plans = mutableListOf<FakePlan>()

  override val address = factory.newAddress("example.com")

  fun addPlan(): FakePlan {
    return FakePlan(nextPlanId++).also {
      plans += it
    }
  }

  override fun isCanceled() = canceled

  override fun plan(): FakePlan {
    require(nextPlanIndex < plans.size) {
      "not enough plans! call addPlan() in the test to set this up"
    }
    val result = plans[nextPlanIndex++]
    events += "take plan ${result.id}"
    return result
  }

  override fun trackFailure(e: IOException) {
    events += "tracking failure: $e"
    hasFailure = true
  }

  override fun hasFailure() = hasFailure

  override fun hasMoreRoutes(): Boolean {
    return nextPlanIndex < plans.size
  }

  override fun sameHostAndPort(url: HttpUrl): Boolean {
    return url.host == address.url.host && url.port == address.url.port
  }

  override fun close() {
    factory.close()
  }

  inner class FakePlan(
    val id: Int
  ) : RoutePlanner.Plan {
    var canceled = false
    var connectState = ConnectState.READY
    val connection = factory.newConnection(pool, factory.newRoute(address))

    override val isConnected: Boolean
      get() = connectState == ConnectState.TLS_CONNECTED

    var tcpConnectDelayNanos = 0L
    var tcpConnectThrowable: Throwable? = null
    var tlsConnectDelayNanos = 0L
    var tlsConnectThrowable: Throwable? = null

    override fun connectTcp(): ConnectResult {
      check(connectState == ConnectState.READY)
      events += "plan $id TCP connecting..."

      taskFaker.sleep(tcpConnectDelayNanos)

      return when {
        tcpConnectThrowable != null -> {
          events += "plan $id TCP connect failed"
          ConnectResult(this, throwable = tcpConnectThrowable)
        }
        canceled -> {
          events += "plan $id TCP connect canceled"
          ConnectResult(this, throwable = IOException("canceled"))
        }
        else -> {
          events += "plan $id TCP connected"
          connectState = ConnectState.TCP_CONNECTED
          ConnectResult(this)
        }
      }
    }

    override fun connectTlsEtc(): ConnectResult {
      check(connectState == ConnectState.TCP_CONNECTED)
      events += "plan $id TLS connecting..."

      taskFaker.sleep(tlsConnectDelayNanos)

      return when {
        tlsConnectThrowable != null -> {
          events += "plan $id TLS connect failed"
          ConnectResult(this, throwable = tlsConnectThrowable)
        }
        canceled -> {
          events += "plan $id TLS connect canceled"
          ConnectResult(this, throwable = IOException("canceled"))
        }
        else -> {
          events += "plan $id TLS connected"
          connectState = ConnectState.TLS_CONNECTED
          ConnectResult(this)
        }
      }
    }

    override fun handleSuccess() = connection

    override fun cancel() {
      events += "plan $id cancel"
      canceled = true
    }
  }

  enum class ConnectState {
    READY,
    TCP_CONNECTED,
    TLS_CONNECTED,
  }
}
