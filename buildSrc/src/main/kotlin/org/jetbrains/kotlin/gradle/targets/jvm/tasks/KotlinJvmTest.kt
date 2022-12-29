// Copied from https://github.com/JetBrains/kotlin/blob/master/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/jvm/tasks/KotlinJvmTest.kt
// which needs to be recompiled with a newer Gradle API to address https://youtrack.jetbrains.com/issue/KT-54634
/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.jvm.tasks

import org.gradle.api.internal.tasks.testing.JvmTestExecutionSpec
import org.gradle.api.internal.tasks.testing.TestDescriptorInternal
import org.gradle.api.internal.tasks.testing.TestExecuter
import org.gradle.api.internal.tasks.testing.TestResultProcessor
import org.gradle.api.internal.tasks.testing.TestStartEvent
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.testing.Test

@CacheableTask
open class KotlinJvmTest : Test() {
  @Input
  @Optional
  var targetName: String? = null

  override fun createTestExecuter(): TestExecuter<JvmTestExecutionSpec> =
    if (targetName != null) Executor(
      super.createTestExecuter(),
      targetName!!
    )
    else super.createTestExecuter()

  class Executor(
    private val delegate: TestExecuter<JvmTestExecutionSpec>,
    private val targetName: String
  ) : TestExecuter<JvmTestExecutionSpec> by delegate {
    override fun execute(testExecutionSpec: JvmTestExecutionSpec, testResultProcessor: TestResultProcessor) {
      delegate.execute(testExecutionSpec, object : TestResultProcessor by testResultProcessor {
        override fun started(test: TestDescriptorInternal, event: TestStartEvent) {
          val myTest = object : TestDescriptorInternal by test {
            override fun getDisplayName(): String = "${test.displayName}[$targetName]"
            override fun getClassName(): String? = test.className?.replace('$', '.')
            override fun getClassDisplayName(): String? = test.classDisplayName?.replace('$', '.')
          }
          testResultProcessor.started(myTest, event)
        }
      })
    }
  }
}
