/*
 * Copyright (C) 2024 Block, Inc.
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
package okhttp3.internal.publicsuffix

import org.junit.runner.Runner
import org.junit.runner.notification.RunNotifier
import org.junit.runners.JUnit4

actual class PublicSuffixTestRunner(
  klass: Class<*>,
) : Runner() {
  private val delegate = JUnit4(klass)

  override fun getDescription() = delegate.description

  override fun run(notifier: RunNotifier?) = delegate.run(notifier)

  override fun testCount() = delegate.testCount()
}

actual fun beforePublicSuffixTest() {
}
