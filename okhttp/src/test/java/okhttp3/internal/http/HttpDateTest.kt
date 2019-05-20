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
    assertThat(HttpDate.parse("Thu, 01 Jan 1970 00:00:00 GMT")!!.time).isEqualTo(0L)
    assertThat(HttpDate.parse("Fri, 06 Jun 2014 12:30:30 GMT")!!.time).isEqualTo(1402057830000L)

    // RFC 850, obsoleted by RFC 1036 with GMT.
    assertThat(HttpDate.parse("Thursday, 01-Jan-70 00:00:00 GMT")!!.time).isEqualTo(0L)
    assertThat(HttpDate.parse("Friday, 06-Jun-14 12:30:30 GMT")!!.time).isEqualTo(1402057830000L)

    // ANSI C's asctime(): should use GMT, not platform default.
    assertThat(HttpDate.parse("Thu Jan 1 00:00:00 1970")!!.time).isEqualTo(0L)
    assertThat(HttpDate.parse("Fri Jun 6 12:30:30 2014")!!.time).isEqualTo(1402057830000L)
  }

  @Test @Throws(Exception::class)
  fun format() {
    assertThat(HttpDate.format(Date(0L))).isEqualTo("Thu, 01 Jan 1970 00:00:00 GMT")
    assertThat(HttpDate.format(Date(1402057830000L))).isEqualTo("Fri, 06 Jun 2014 12:30:30 GMT")
  }

  @Test @Throws(Exception::class)
  fun parseNonStandardStrings() {
    // RFC 822, updated by RFC 1123 with any TZ
    assertThat(HttpDate.parse("Thu, 01 Jan 1970 00:00:00 GMT-01:00")!!.time).isEqualTo(3600000L)
    assertThat(HttpDate.parse("Thu, 01 Jan 1970 00:00:00 PST")!!.time).isEqualTo(28800000L)
    // Ignore trailing junk
    assertThat(HttpDate.parse("Thu, 01 Jan 1970 00:00:00 GMT JUNK")!!.time).isEqualTo(0L)
    // Missing timezones treated as bad.
    assertThat(HttpDate.parse("Thu, 01 Jan 1970 00:00:00")).isNull()
    // Missing seconds treated as bad.
    assertThat(HttpDate.parse("Thu, 01 Jan 1970 00:00 GMT")).isNull()
    // Extra spaces treated as bad.
    assertThat(HttpDate.parse("Thu,  01 Jan 1970 00:00 GMT")).isNull()
    // Missing leading zero treated as bad.
    assertThat(HttpDate.parse("Thu, 1 Jan 1970 00:00 GMT")).isNull()

    // RFC 850, obsoleted by RFC 1036 with any TZ.
    assertThat(HttpDate.parse("Thursday, 01-Jan-1970 00:00:00 GMT-01:00")!!.time)
        .isEqualTo(3600000L)
    assertThat(HttpDate.parse("Thursday, 01-Jan-1970 00:00:00 PST")!!.time)
        .isEqualTo(28800000L)
    // Ignore trailing junk
    assertThat(HttpDate.parse("Thursday, 01-Jan-1970 00:00:00 PST JUNK")!!.time)
        .isEqualTo(28800000L)

    // ANSI C's asctime() format
    // This format ignores the timezone entirely even if it is present and uses GMT.
    assertThat(HttpDate.parse("Fri Jun 6 12:30:30 2014 PST")!!.time).isEqualTo(1402057830000L)
    // Ignore trailing junk.
    assertThat(HttpDate.parse("Fri Jun 6 12:30:30 2014 JUNK")!!.time).isEqualTo(1402057830000L)
  }
}
