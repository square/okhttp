/*
 * Copyright (C) 2020 Square, Inc.
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

import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import java.io.OutputStream
import java.io.PrintStream

object DotListener: TestExecutionListener {
  private var originalSystemErr: PrintStream? = null
  private var originalSystemOut: PrintStream? = null
  private var testCount = 0

  override fun executionSkipped(testIdentifier: TestIdentifier, reason: String) {
    printStatus("-")
  }

  private fun printStatus(s: String) {
    if (++testCount % 80 == 0) {
      printStatus("\n")
    }
    originalSystemErr?.print(s)
  }

  override fun executionFinished(
    testIdentifier: TestIdentifier,
    testExecutionResult: TestExecutionResult
  ) {
    if (!testIdentifier.isContainer) {
      when (testExecutionResult.status!!) {
        TestExecutionResult.Status.ABORTED -> printStatus("-")
        TestExecutionResult.Status.FAILED -> printStatus("F")
        TestExecutionResult.Status.SUCCESSFUL -> printStatus(".")
      }
    }
  }

  override fun testPlanExecutionFinished(testPlan: TestPlan) {
    originalSystemErr?.println()
  }

  fun install() {
    originalSystemOut = System.out
    originalSystemErr = System.err

    System.setOut(object: PrintStream(OutputStream.nullOutputStream()) {})
    System.setErr(object: PrintStream(OutputStream.nullOutputStream()) {})
  }

  fun uninstall() {
    originalSystemOut.let {
      System.setOut(it)
    }
    originalSystemErr.let {
      System.setErr(it)
    }
  }
}