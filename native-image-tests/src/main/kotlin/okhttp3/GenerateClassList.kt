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

import org.junit.jupiter.engine.descriptor.ClassBasedTestDescriptor
import org.junit.platform.engine.discovery.DiscoverySelectors
import java.io.File

// TODO move to junit5 tags
val avoidedTests = setOf(
  "okhttp3.BouncyCastleTest",
  "okhttp3.ConscryptTest",
  "okhttp3.CorrettoTest",
  "okhttp3.OpenJSSETest",
  "okhttp3.internal.platform.Jdk8WithJettyBootPlatformTest",
  "okhttp3.internal.platform.Jdk9PlatformTest",
  "okhttp3.internal.platform.PlatformTest",
  "okhttp3.internal.platform.android.AndroidSocketAdapterTest",
  "okhttp3.osgi.OsgiTest",
  "okhttp3.CookiesTest", // hanging
  "okhttp3.WholeOperationTimeoutTest", // hanging
)

/**
 * Run periodically to refresh the known set of working tests.
 *
 * TODO use filtering to allow skipping acceptable problem tests
 */
fun main() {
  val knownTestFile = File("native-image-tests/src/main/resources/testlist.txt")
  val testSelector = DiscoverySelectors.selectPackage("okhttp3")
  val testClasses = findTests(listOf(testSelector))
    .filter { it.isContainer }
    .mapNotNull { (it as? ClassBasedTestDescriptor)?.testClass?.name }
    .filterNot { it in avoidedTests }
    .sorted()
    .distinct()
  knownTestFile.writeText(testClasses.joinToString("\n"))
}