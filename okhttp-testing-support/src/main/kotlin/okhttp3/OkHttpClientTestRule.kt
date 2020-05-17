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
package okhttp3

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogManager
import java.util.logging.LogRecord
import java.util.logging.Logger
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.http2.Http2
import okhttp3.testing.Flaky
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Apply this rule to all tests. It adds additional checks for leaked resources and uncaught
 * exceptions.
 *
 * Use [newClient] as a factory for a OkHttpClient instances. These instances are specifically
 * configured for testing.
 */
class OkHttpClientTestRule : TestRule {
  private val clientEventsList = mutableListOf<String>()
  private var testClient: OkHttpClient? = null
  private var uncaughtException: Throwable? = null
  var logger: Logger? = null
  lateinit var testName: String

  var recordEvents = true
  var recordTaskRunner = false
  var recordFrames = false
  var recordSslDebug = false

  private val testLogHandler = object : Handler() {
    override fun publish(record: LogRecord) {
      val name = record.loggerName
      val recorded = when (name) {
        TaskRunner::class.java.name -> recordTaskRunner
        Http2::class.java.name -> recordFrames
        "javax.net.ssl" -> recordSslDebug
        else -> false
      }

      if (recorded) {
        synchronized(clientEventsList) {
          clientEventsList.add(record.message)

          if (record.loggerName == "javax.net.ssl") {
            val parameters = record.parameters

            if (parameters != null) {
              clientEventsList.add(parameters.first().toString())
            }
          }
        }
      }
    }

    override fun flush() {
    }

    override fun close() {
    }
  }.apply {
    level = Level.FINEST
  }

  private fun applyLogger(fn: Logger.() -> Unit) {
    Logger.getLogger(OkHttpClient::class.java.`package`.name).fn()
    Logger.getLogger(OkHttpClient::class.java.name).fn()
    Logger.getLogger(Http2::class.java.name).fn()
    Logger.getLogger(TaskRunner::class.java.name).fn()
    Logger.getLogger("javax.net.ssl").fn()
  }

  fun wrap(eventListener: EventListener) = object : EventListener.Factory {
    override fun create(call: Call) = ClientRuleEventListener(eventListener) { addEvent(it) }
  }

  fun wrap(eventListenerFactory: EventListener.Factory) = object : EventListener.Factory {
    override fun create(call: Call) =
      ClientRuleEventListener(eventListenerFactory.create(call)) { addEvent(it) }
  }

  /**
   * Returns an OkHttpClient for tests to use as a starting point.
   *
   * The returned client installs a default event listener that gathers debug information. This will
   * be logged if the test fails.
   *
   * This client is also configured to be slightly more deterministic, returning a single IP
   * address for all hosts, regardless of the actual number of IP addresses reported by DNS.
   */
  fun newClient(): OkHttpClient {
    var client = testClient
    if (client == null) {
      client = OkHttpClient.Builder()
          .dns(SINGLE_INET_ADDRESS_DNS) // Prevent unexpected fallback addresses.
          .eventListenerFactory(object : EventListener.Factory {
            override fun create(call: Call) = ClientRuleEventListener { addEvent(it) }
          })
          .build()
      testClient = client
    }
    return client
  }

  fun newClientBuilder(): OkHttpClient.Builder {
    return newClient().newBuilder()
  }

  @Synchronized private fun addEvent(event: String) {
    if (recordEvents) {
      logger?.info(event)

      synchronized(clientEventsList) {
        clientEventsList.add(event)
      }
    }
  }

  @Synchronized private fun initUncaughtException(throwable: Throwable) {
    if (uncaughtException == null) {
      uncaughtException = throwable
    }
  }

  fun ensureAllConnectionsReleased() {
    testClient?.let {
      val connectionPool = it.connectionPool

      connectionPool.evictAll()
      if (connectionPool.connectionCount() > 0) {
        // Minimise test flakiness due to possible race conditions with connections closing.
        // Some number of tests will report here, but not fail due to this delay.
        println("Delaying to avoid flakes")
        Thread.sleep(500L)
        println("After delay: " + connectionPool.connectionCount())
      }

      assertEquals(0, connectionPool.connectionCount())
    }
  }

  private fun ensureAllTaskQueuesIdle() {
    val entryTime = System.nanoTime()

    for (queue in TaskRunner.INSTANCE.activeQueues()) {
      // We wait at most 1 second, so we don't ever turn multiple lost threads into
      // a test timeout failure.
      val waitTime = (entryTime + 1_000_000_000L - System.nanoTime())
      if (!queue.idleLatch().await(waitTime, TimeUnit.NANOSECONDS)) {
        TaskRunner.INSTANCE.cancelAll()
        fail("Queue still active after 1000 ms")
      }
    }
  }

  override fun apply(
    base: Statement,
    description: Description
  ): Statement {
    return object : Statement() {
      override fun evaluate() {
        testName = description.methodName

        val defaultUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
          initUncaughtException(throwable)
        }
        val taskQueuesWereIdle = TaskRunner.INSTANCE.activeQueues().isEmpty()
        var failure: Throwable? = null
        try {
          applyLogger {
            addHandler(testLogHandler)
            level = Level.FINEST
            useParentHandlers = false
          }

          base.evaluate()
          if (uncaughtException != null) {
            throw AssertionError("uncaught exception thrown during test", uncaughtException)
          }
          logEventsIfFlaky(description)
        } catch (t: Throwable) {
          failure = t
          logEvents()
          throw t
        } finally {
          LogManager.getLogManager().reset()

          Thread.setDefaultUncaughtExceptionHandler(defaultUncaughtExceptionHandler)
          try {
            ensureAllConnectionsReleased()
            releaseClient()
          } catch (ae: AssertionError) {
            // Prefer keeping the inflight failure, but don't release this in-use client.
            if (failure != null) {
              failure.addSuppressed(ae)
            } else {
              failure = ae
            }
          }

          try {
            if (taskQueuesWereIdle) {
              ensureAllTaskQueuesIdle()
            }
          } catch (ae: AssertionError) {
            // Prefer keeping the inflight failure, but don't release this in-use client.
            if (failure != null) {
              failure.addSuppressed(ae)
            } else {
              failure = ae
            }
          }

          if (failure != null) {
            throw failure
          }
        }
      }

      private fun releaseClient() {
        testClient?.dispatcher?.executorService?.shutdown()
      }
    }
  }

  private fun logEventsIfFlaky(description: Description) {
    if (isTestFlaky(description)) {
      logEvents()
    }
  }

  private fun isTestFlaky(description: Description): Boolean {
    return description.annotations.any { it.annotationClass == Flaky::class } ||
        description.testClass.annotations.any { it.annotationClass == Flaky::class }
  }

  @Synchronized private fun logEvents() {
    // Will be ineffective if test overrides the listener
    synchronized(clientEventsList) {
      println("$testName Events (${clientEventsList.size})")

      for (e in clientEventsList) {
        println(e)
      }
    }
  }

  companion object {
    /**
     * A network that resolves only one IP address per host. Use this when testing route selection
     * fallbacks to prevent the host machine's various IP addresses from interfering.
     */
    private val SINGLE_INET_ADDRESS_DNS = object : Dns {
      override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        return listOf(addresses[0])
      }
    }
  }
}
