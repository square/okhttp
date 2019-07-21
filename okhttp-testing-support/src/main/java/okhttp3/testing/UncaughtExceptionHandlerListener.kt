/*
 * Copyright (C) 2015 Square, Inc.
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
package okhttp3.testing

import org.junit.runner.Description
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.Thread.UncaughtExceptionHandler

/**
 * installs an aggressive default [UncaughtExceptionHandler] similar to the one found on Android.
 * No exceptions should escape from OkHttp that might cause apps to be killed or tests to fail on
 * Android.
 */
object UncaughtExceptionHandlerListener {
  private var runningTests = mutableSetOf<Description>()
  private val exceptions: MutableList<Pair<Throwable, String>> = mutableListOf()

  init {
    Thread.setDefaultUncaughtExceptionHandler { thread: Thread, throwable: Throwable ->
      val errorText = StringWriter(256)
      errorText.append("Uncaught exception in OkHttp thread \"")
      errorText.append(thread.name)
      errorText.append("\"\n")
      throwable.printStackTrace(PrintWriter(errorText))
      errorText.append("\n")

      val testNames = runningTestNames()

      errorText.append("Running tests was: ")
      errorText.append(testNames)
      errorText.append("\n")

      System.err.print(errorText.toString())
      synchronized(exceptions) { exceptions.add(Pair(throwable, testNames)) }
    }

    Runtime.getRuntime().addShutdownHook(Thread {
      val possiblyRelatedExceptions = drainExceptions()

      if (possiblyRelatedExceptions.isNotEmpty()) {
        System.err.println("Uncaught exceptions not associated with tests")
        for ((_, info) in possiblyRelatedExceptions) {
          System.err.println(info)
        }
      }
    })
  }

  @Synchronized private fun runningTestNames(): String =
      runningTests.joinToString { it.displayName }

  @Synchronized fun testStarted(description: Description) {
    runningTests.add(description)
  }

  @Synchronized fun testPassed(description: Description) {
    testCompleted(description, true)
  }

  @Synchronized fun testFailed(description: Description) {
    testCompleted(description, false)
  }

  @Synchronized fun testCompleted(description: Description, triggerFailure: Boolean) {
    runningTests.remove(description)

    val possiblyRelatedExceptions = drainExceptions()

    if (possiblyRelatedExceptions.isNotEmpty()) {
      System.err.println("Uncaught exceptions not associated with tests")
      for ((_, info) in possiblyRelatedExceptions) {
        System.err.println(info)
      }
    }
  }

  private fun drainExceptions(): List<Pair<Throwable, String>> {
    synchronized(exceptions) {
      return if (exceptions.isNotEmpty()) {
        exceptions.takeWhile { true }
      } else {
        listOf()
      }
    }
  }
}