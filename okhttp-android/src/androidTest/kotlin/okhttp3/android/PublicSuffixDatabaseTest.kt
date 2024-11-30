/*
 * Copyright (c) 2022 Square, Inc.
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
 *
 */
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package okhttp3.android

import assertk.assertThat
import assertk.assertions.isNotNull
import okhttp3.internal.platform.Platform
import okhttp3.internal.platform.android.AndroidContextPlatform
import okhttp3.internal.publicsuffix.PublicSuffixDatabase
import org.junit.Test

/**
 * Run with "./gradlew :android-test:connectedCheck -PandroidBuild=true" and make sure ANDROID_SDK_ROOT is set.
 */
class PublicSuffixDatabaseTest {

  @Test
  fun testFromAsset() {
    assertThat((Platform.get() as AndroidContextPlatform).context).isNotNull()

    val db = PublicSuffixDatabase.get()
    db.getEffectiveTldPlusOne("www.smh.com.au")
  }
}
