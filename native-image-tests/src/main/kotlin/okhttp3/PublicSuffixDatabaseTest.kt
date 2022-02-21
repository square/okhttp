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
import org.junit.jupiter.api.Test

class PublicSuffixDatabaseTest {
  @Test
  fun testResourcesLoaded() {
    val url = "https://api.twitter.com".toHttpUrl()

    assertThat(url.topPrivateDomain()).isEqualTo("twitter.com")
  }

  @Test
  fun testPublicSuffixes() {
    PublicSuffixDatabase::class.java.getResourceAsStream(PublicSuffixDatabase.PUBLIC_SUFFIX_RESOURCE).use {
      assertThat(it.available()).isGreaterThan(30000)
    }
  }
}
