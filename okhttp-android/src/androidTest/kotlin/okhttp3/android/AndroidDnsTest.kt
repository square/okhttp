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
package okhttp3.android

import assertk.assertFailure
import assertk.assertions.cause
import assertk.assertions.hasMessage
import assertk.assertions.hasNoCause
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import java.net.UnknownHostException
import okhttp3.Dns
import org.junit.Test

class AndroidDnsTest {
  @Test
  fun testDnsRequestInvalid() {
    assertFailure {
      AndroidDns.lookup("google.invalid")
    }.apply {
      hasMessage("Unable to resolve host \"google.invalid\": No address associated with hostname")
      isInstanceOf(UnknownHostException::class)
      cause().isNotNull().hasMessage("android_getaddrinfo failed: EAI_NODATA (No address associated with hostname)")
    }

    assertFailure {
      AndroidDns.lookup("google.invalid")
    }.apply {
      hasMessage("Unable to resolve host \"google.invalid\": No address associated with hostname")
      isInstanceOf(UnknownHostException::class)
      cause().isNotNull().hasMessage("android_getaddrinfo failed: EAI_NODATA (No address associated with hostname)")
    }
  }
  @Test
  fun testDnsRequestInvalidWithSystem() {
    assertFailure {
      Dns.SYSTEM.lookup("google.invalid")
    }.apply {
      hasMessage("Unable to resolve host \"google.invalid\": No address associated with hostname")
      isInstanceOf(UnknownHostException::class)
      cause().isNotNull().hasMessage("android_getaddrinfo failed: EAI_NODATA (No address associated with hostname)")
    }

    assertFailure {
      Dns.SYSTEM.lookup("google.invalid")
    }.apply {
      hasMessage("Unable to resolve host \"google.invalid\": No address associated with hostname")
      isInstanceOf(UnknownHostException::class)
      hasNoCause()
    }
  }
}
