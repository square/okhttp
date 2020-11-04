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
package okhttp3.nativeImage

import okhttp3.SampleTest
import okhttp3.findTests
import okhttp3.testSelectors
import okhttp3.treeListener
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.engine.descriptor.ClassBasedTestDescriptor
import org.junit.platform.engine.discovery.DiscoverySelectors

class NativeImageTestsTest {
  @Test
  fun testFindsFixedTestsForImage() {
    val testSelector = testSelectors()
    val x = findTests(testSelector)

    x.find { it is ClassBasedTestDescriptor && it.testClass == SampleTest::class.java }
  }

  @Test
  fun testFindsModuleTests() {
    val testSelector = DiscoverySelectors.selectPackage("okhttp3")
    val x = findTests(listOf(testSelector))

    x.find { it is ClassBasedTestDescriptor && it.testClass == SampleTest::class.java }
  }

  @Test
  fun testFindsProjectTests() {
    val testSelector = DiscoverySelectors.selectPackage("okhttp3")
    val x = findTests(listOf(testSelector))

    x.find { it is ClassBasedTestDescriptor && it.testClass == SampleTest::class.java }
  }

  @Test
  fun testTreeListener() {
    val listener = treeListener()

    assertNotNull(listener)
  }
}