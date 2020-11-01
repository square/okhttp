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

import com.oracle.svm.core.annotate.AutomaticFeature
import org.graalvm.nativeimage.hosted.Feature
import org.graalvm.nativeimage.hosted.RuntimeReflection
import java.io.File

@AutomaticFeature
class TestRegistration : Feature {
  override fun beforeAnalysis(access: Feature.BeforeAnalysisAccess) {
    registerKnownTests(access)

    val listener = Class.forName("org.junit.platform.console.tasks.TreePrintingListener")
    RuntimeReflection.register(listener)
    listener.declaredConstructors.forEach {
      RuntimeReflection.register(it)
    }

    val timeoutResource =
      Class.forName("org.junit.jupiter.engine.extension.TimeoutExtension\$ExecutorResource")
    RuntimeReflection.register(timeoutResource)
    timeoutResource.declaredConstructors.forEach {
      RuntimeReflection.register(it)
    }
  }

  private fun registerKnownTests(access: Feature.BeforeAnalysisAccess) {
    val knownTestFile = File("src/main/resources/testlist.txt").absoluteFile
    knownTestFile.readLines().forEach {
      try {
        val testClass = access.findClassByName(it)

        if (testClass != null) {
          access.registerAsUsed(testClass)
          registerTest(testClass)
        }
      } catch (e: Exception) {
        // If you throw an exception here then native image building fails half way through
        // silently without rewriting the binary. So we report noisily, but keep going and prefer
        // running most tests still.
        e.printStackTrace()
      }
    }
  }

  private fun registerTest(java: Class<*>) {
    RuntimeReflection.register(java)
    java.constructors.forEach {
      RuntimeReflection.register(it)
    }
    java.declaredMethods.forEach {
      RuntimeReflection.register(it)
    }
    java.declaredFields.forEach {
      RuntimeReflection.register(it)
    }
  }
}