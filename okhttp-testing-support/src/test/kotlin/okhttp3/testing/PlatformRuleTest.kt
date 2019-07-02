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
package okhttp3.testing

import okhttp3.internal.platform.Platform
import org.junit.Rule
import org.junit.Test

/**
 * Sanity test for checking which environment and IDE is picking up.
 */
class PlatformRuleTest {
  @Suppress("RedundantVisibilityModifier")
  @JvmField
  @Rule public val platform = PlatformRule()

  @Test
  fun testMode() {
    println(PlatformRule.getPlatformSystemProperty())
    println(Platform.get().javaClass.simpleName)
  }
  @Test
  fun testGreenCase() {
  }

  @Test
  fun testGreenCaseFailingOnLater() {
    platform.expectFailureFromJdkVersion(PlatformVersion.majorVersion + 1)
  }

  @Test
  fun failureCase() {
    platform.expectFailureFromJdkVersion(PlatformVersion.majorVersion)

    check(false)
  }
}