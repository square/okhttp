/*
 * Copyright (C) 2022 Square, Inc.
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

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.internal.publicsuffix.PublicSuffixDatabase
import okhttp3.testing.PlatformRule
import okio.FileSystem
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class PublicSuffixDatabaseTest {
  @RegisterExtension
  @JvmField
  val platform = PlatformRule()

  @Test
  fun testResourcesLoaded() {
    val url = "https://api.twitter.com".toHttpUrl()

    assertThat(url.topPrivateDomain()).isEqualTo("twitter.com")
  }

  @Test
  fun testPublicSuffixes() {
    platform.assumeNotGraalVMImage()

    val metadata = FileSystem.RESOURCES.metadata(PublicSuffixDatabase.PUBLIC_SUFFIX_RESOURCE)
    assertThat(metadata.size!!).isGreaterThan(30000)
  }
}
