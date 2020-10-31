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

object DotListener: TestExecutionListener {
  override fun executionSkipped(testIdentifier: TestIdentifier, reason: String) {
    System.err.print("-")
  }

  override fun executionFinished(
    testIdentifier: TestIdentifier,
    testExecutionResult: TestExecutionResult
  ) {
    when (testExecutionResult.status) {
      TestExecutionResult.Status.ABORTED -> System.err.print("E")
      TestExecutionResult.Status.FAILED -> System.err.print("F")
      TestExecutionResult.Status.SUCCESSFUL -> System.err.print(".")
    }
  }

  override fun testPlanExecutionStarted(testPlan: TestPlan?) {
    println()
  }

  override fun testPlanExecutionFinished(testPlan: TestPlan) {
    System.err.println()
  }
}