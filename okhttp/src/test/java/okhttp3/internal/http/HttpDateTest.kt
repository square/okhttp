/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3.internal.http

import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Date
import java.util.TimeZone

class HttpDateTest {

  private lateinit var originalDefault: TimeZone

  @Before
  @Throws(Exception::class)
  fun setUp() {
    originalDefault = TimeZone.getDefault()
    // The default timezone should affect none of these tests: HTTP specified GMT, so we set it to
    // something else.
    TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
  }

  @After
  @Throws(Exception::class)
  fun tearDown() {
    TimeZone.setDefault(originalDefault)
  }

  @Test @Throws(Exception::class)
  fun parseStandardFormats() {
    // RFC 822, updated by RFC 1123 with GMT.
    assertThat("Thu, 01 Jan 1970 00:00:00 GMT".toHttpDateOrNull()!!.time).isEqualTo(0L)
    assertThat("Fri, 06 Jun 2014 12:30:30 GMT".toHttpDateOrNull()!!.time).isEqualTo(1402057830000L)

    // RFC 850, obsoleted by RFC 1036 with GMT.
    assertThat("Thursday, 01-Jan-70 00:00:00 GMT".toHttpDateOrNull()!!.time).isEqualTo(0L)
    assertThat("Friday, 06-Jun-14 12:30:30 GMT".toHttpDateOrNull()!!.time).isEqualTo(1402057830000L)

    // ANSI C's asctime(): should use GMT, not platform default.
    assertThat("Thu Jan 1 00:00:00 1970".toHttpDateOrNull()!!.time).isEqualTo(0L)
    assertThat("Fri Jun 6 12:30:30 2014".toHttpDateOrNull()!!.time).isEqualTo(1402057830000L)
  }

  @Test @Throws(Exception::class)
  fun format() {
    assertThat(Date(0L).toHttpDateString()).isEqualTo("Thu, 01 Jan 1970 00:00:00 GMT")
    assertThat(Date(1402057830000L).toHttpDateString()).isEqualTo("Fri, 06 Jun 2014 12:30:30 GMT")
  }

  @Test @Throws(Exception::class)
  fun parseNonStandardStrings() {
    // RFC 822, updated by RFC 1123 with any TZ
    assertThat("Thu, 01 Jan 1970 00:00:00 GMT-01:00".toHttpDateOrNull()!!.time).isEqualTo(3600000L)
    assertThat("Thu, 01 Jan 1970 00:00:00 PST".toHttpDateOrNull()!!.time).isEqualTo(28800000L)
    // Ignore trailing junk
    assertThat("Thu, 01 Jan 1970 00:00:00 GMT JUNK".toHttpDateOrNull()!!.time).isEqualTo(0L)
    // Missing timezones treated as bad.
    assertThat("Thu, 01 Jan 1970 00:00:00".toHttpDateOrNull()).isNull()
    // Missing seconds treated as bad.
    assertThat("Thu, 01 Jan 1970 00:00 GMT".toHttpDateOrNull()).isNull()
    // Extra spaces treated as bad.
    assertThat("Thu,  01 Jan 1970 00:00 GMT".toHttpDateOrNull()).isNull()
    // Missing leading zero treated as bad.
    assertThat("Thu, 1 Jan 1970 00:00 GMT".toHttpDateOrNull()).isNull()

    // RFC 850, obsoleted by RFC 1036 with any TZ.
    assertThat("Thursday, 01-Jan-1970 00:00:00 GMT-01:00".toHttpDateOrNull()!!.time)
        .isEqualTo(3600000L)
    assertThat("Thursday, 01-Jan-1970 00:00:00 PST".toHttpDateOrNull()!!.time)
        .isEqualTo(28800000L)
    // Ignore trailing junk
    assertThat("Thursday, 01-Jan-1970 00:00:00 PST JUNK".toHttpDateOrNull()!!.time)
        .isEqualTo(28800000L)

    // ANSI C's asctime() format
    // This format ignores the timezone entirely even if it is present and uses GMT.
    assertThat("Fri Jun 6 12:30:30 2014 PST".toHttpDateOrNull()!!.time).isEqualTo(1402057830000L)
    // Ignore trailing junk.
    assertThat("Fri Jun 6 12:30:30 2014 JUNK".toHttpDateOrNull()!!.time).isEqualTo(1402057830000L)
  }
}
